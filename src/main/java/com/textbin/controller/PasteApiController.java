package com.textbin.controller;

import com.textbin.dto.PasteResponse;
import com.textbin.dto.PasteStatusDto;
import com.textbin.dto.SavePasteRequest;
import com.textbin.model.Paste;
import com.textbin.service.FileStorageService;
import com.textbin.service.PasteService;
import com.textbin.service.PasteSyncService;
import com.textbin.service.QrCodeService;
import com.textbin.util.NetworkUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.textbin.service.PasteSecurityService;
import com.textbin.service.RateLimitingService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/d", "/api/dropbin", "/api/pastes"})
public class PasteApiController {

    private final PasteService pasteService;
    private final PasteSyncService syncService;
    private final QrCodeService qrCodeService;
    private final FileStorageService fileStorageService;
    private final PasteSecurityService pasteSecurityService;
    private final RateLimitingService rateLimitingService;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${app.base-url:}")
    private String configuredBaseUrl;

    public PasteApiController(PasteService pasteService, PasteSyncService syncService,
                              QrCodeService qrCodeService, FileStorageService fileStorageService,
                              PasteSecurityService pasteSecurityService, RateLimitingService rateLimitingService) {
        this.pasteService = pasteService;
        this.syncService = syncService;
        this.qrCodeService = qrCodeService;
        this.fileStorageService = fileStorageService;
        this.pasteSecurityService = pasteSecurityService;
        this.rateLimitingService = rateLimitingService;
    }

    /**
     * Auto-save / debounce persistence endpoint.
     */
    @PostMapping("/{id:[a-zA-Z0-9_\\-]{1,64}}")
    public ResponseEntity<?> savePaste(
            @PathVariable String id,
            @RequestBody SavePasteRequest request,
            HttpServletRequest httpRequest
    ) {
        String cleanId = PasteService.sanitizeId(id);
        // Enforce server-side security: Reject edits if accessed with a read-only key
        if (pasteService.isReadOnlyKey(cleanId) || cleanId.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "This bin is being accessed in read-only mode and cannot be edited."));
        }

        Optional<Paste> existing = pasteService.findById(cleanId);
        if (existing.isPresent() && existing.get().isLocked() && !pasteSecurityService.isAuthorized(httpRequest, existing.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("error", "Workspace is locked. Enter password to save.", "locked", true));
        }

        Paste saved = pasteService.saveOrUpdate(
                cleanId,
                request.getContent(),
                request.getTitle(),
                request.getSyntaxLanguage(),
                request.getClientToken()
        );

        PasteResponse resp = new PasteResponse(
                saved.getId(),
                saved.getContent(),
                saved.getTitle(),
                saved.getSyntaxLanguage(),
                saved.getVersion(),
                saved.getUpdatedAt(),
                request.getClientToken(),
                saved.isLocked()
        );
        return ResponseEntity.ok(resp);
    }

    /**
     * Takedown / Delete bin immediately (wiping text and all attached files).
     */
    @DeleteMapping({"/{id:[a-zA-Z0-9_\\-]{1,64}}", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}"})
    public ResponseEntity<?> takedownPasteByDelete(@PathVariable String id, HttpServletRequest request) {
        return processTakedown(id, request);
    }

    /**
     * Fallback endpoint for environments where DELETE is restricted or converted to POST.
     */
    @PostMapping({"/{id:[a-zA-Z0-9_\\-]{1,64}}/takedown", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/takedown"})
    public ResponseEntity<?> takedownPasteByPost(@PathVariable String id, HttpServletRequest request) {
        return processTakedown(id, request);
    }

    private ResponseEntity<?> processTakedown(String id, HttpServletRequest request) {
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "Cannot take down a bin in read-only mode."));
        }

        String cleanId = PasteService.sanitizeId(id);
        Optional<Paste> opt = pasteService.findById(cleanId);
        if (opt.isPresent() && opt.get().isLocked() && !pasteSecurityService.isAuthorized(request, opt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("error", "Workspace is locked. Enter password to take down.", "locked", true));
        }

        // 1. Delete all attached files from disk and DB
        try {
            fileStorageService.deleteAllFilesForPaste(cleanId);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PasteApiController.class)
                    .warn("Non-fatal error deleting files during takedown for {}: {}", cleanId, e.getMessage());
        }

        // 2. Broadcast bin_deleted event to notify all connected peers
        try {
            syncService.broadcastEvent(cleanId, "bin_deleted", java.util.Map.of(
                    "pasteId", cleanId,
                    "message", "This bin has been taken down and deleted."
            ));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PasteApiController.class)
                    .warn("Non-fatal error broadcasting takedown for {}: {}", cleanId, e.getMessage());
        }

        // 3. Immediately close and terminate active SSE streams for this paste
        try {
            syncService.closeEmitters(cleanId);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PasteApiController.class)
                    .warn("Non-fatal error closing SSE emitters for {}: {}", cleanId, e.getMessage());
        }

        // 4. Delete the paste from database
        try {
            pasteService.deletePaste(cleanId);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PasteApiController.class)
                    .warn("Non-fatal error deleting paste for {}: {}", cleanId, e.getMessage());
        }

        return ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "pasteId", cleanId,
                "message", "Bin taken down successfully"
        ));
    }

    /**
     * Unlock a password-protected workspace.
     */
    @PostMapping({"/{id:[a-zA-Z0-9_\\-]{1,64}}/unlock", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/unlock"})
    public ResponseEntity<?> unlockWorkspace(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String clientIp = RateLimitingService.getClientIp(request);
        if (!rateLimitingService.allowRequest(clientIp, "unlock", 5)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many unlock attempts. Please wait 1 minute."));
        }

        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Workspace not found."));
        }

        Paste paste = opt.get();
        if (!paste.isLocked()) {
            return ResponseEntity.ok(Map.of("success", true, "unlocked", true));
        }

        String password = body.get("password");
        if (password == null || !paste.verifyPassword(password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Incorrect password. Please try again."));
        }

        String token = pasteSecurityService.generateToken(paste);
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = pasteSecurityService.buildAuthCookie(paste, token, isSecure);
        pasteSecurityService.setAuthCookie(response, paste, token, isSecure);

        pasteService.touch(paste.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of(
                        "success", true,
                        "unlocked", true,
                        "token", token
                ));
    }

    /**
     * Set, update, or remove workspace password.
     */
    @PostMapping("/{id:[a-zA-Z0-9_\\-]{1,64}}/password")
    public ResponseEntity<?> setOrUpdatePassword(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot modify password in read-only mode."));
        }

        String cleanId = PasteService.sanitizeId(id);
        Paste paste = pasteService.getOrCreate(cleanId);

        String currentPassword = body.get("currentPassword");
        if (paste.isLocked()) {
            boolean authorized = pasteSecurityService.isAuthorized(request, paste);
            boolean matches = currentPassword != null && paste.verifyPassword(currentPassword);
            if (!authorized && !matches) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Current password is required and must be correct to modify lock."));
            }
        }

        String newPassword = body.get("newPassword");
        boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));

        if (newPassword == null || newPassword.isBlank()) {
            pasteService.removePassword(cleanId);
            ResponseCookie clearCookie = pasteSecurityService.buildClearAuthCookie(cleanId);
            pasteSecurityService.clearAuthCookie(response, cleanId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                    .body(Map.of(
                            "success", true,
                            "locked", false,
                            "message", "Password lock removed successfully."
                    ));
        } else {
            if (newPassword.trim().length() < 3) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Password must be at least 3 characters."));
            }
            Paste updated = pasteService.setPassword(cleanId, newPassword.trim());
            String token = pasteSecurityService.generateToken(updated);
            ResponseCookie cookie = pasteSecurityService.buildAuthCookie(updated, token, isSecure);
            pasteSecurityService.setAuthCookie(response, updated, token, isSecure);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(Map.of(
                            "success", true,
                            "locked", true,
                            "token", token,
                            "message", "Workspace is now password-protected."
                    ));
        }
    }

    /**
     * Remove password protection from a workspace (DELETE).
     */
    @DeleteMapping("/{id:[a-zA-Z0-9_\\-]{1,64}}/password")
    public ResponseEntity<?> deletePassword(
            @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return removePasswordInternal(id, request, response);
    }

    /**
     * Remove password protection from a workspace (POST fallback).
     */
    @PostMapping("/{id:[a-zA-Z0-9_\\-]{1,64}}/password/remove")
    public ResponseEntity<?> removePasswordViaPost(
            @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return removePasswordInternal(id, request, response);
    }

    private ResponseEntity<?> removePasswordInternal(String id, HttpServletRequest request, HttpServletResponse response) {
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot modify password in read-only mode."));
        }

        String cleanId = PasteService.sanitizeId(id);
        Paste paste = pasteService.getOrCreate(cleanId);

        if (paste.isLocked() && !pasteSecurityService.isAuthorized(request, paste)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized. Enter password first."));
        }

        pasteService.removePassword(cleanId);
        ResponseCookie clearCookie = pasteSecurityService.buildClearAuthCookie(cleanId);
        pasteSecurityService.clearAuthCookie(response, cleanId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(Map.of(
                        "success", true,
                        "locked", false,
                        "message", "Password lock removed successfully."
                ));
    }

    /**
     * Lightweight status check (version, timestamp, length) for polling sync.
     */
    @GetMapping({"/{id:[a-zA-Z0-9_\\-]{1,64}}/status", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/status"})
    public ResponseEntity<PasteStatusDto> getStatus(@PathVariable String id, HttpServletRequest request) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Paste p = opt.get();
        if (p.isLocked() && !pasteSecurityService.isAuthorized(request, p)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new PasteStatusDto(p.getId(), 0L, p.getUpdatedAt(), 0, true));
        }
        int len = p.getContent() != null ? p.getContent().length() : 0;
        return ResponseEntity.ok(new PasteStatusDto(p.getId(), p.getVersion(), p.getUpdatedAt(), len, p.isLocked()));
    }

    /**
     * Full details for a paste.
     */
    @GetMapping({"/{id:[a-zA-Z0-9_\\-]{1,64}}", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}"})
    public ResponseEntity<PasteResponse> getPaste(@PathVariable String id, HttpServletRequest request) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Paste p = opt.get();
        if (p.isLocked() && !pasteSecurityService.isAuthorized(request, p)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new PasteResponse(
                p.getId(),
                p.getContent(),
                p.getTitle(),
                p.getSyntaxLanguage(),
                p.getVersion(),
                p.getUpdatedAt(),
                null,
                p.isLocked()
        ));
    }

    /**
     * Server-Sent Events stream for instant real-time push to other devices.
     */
    @GetMapping(value = {"/{id:[a-zA-Z0-9_\\-]{1,64}}/stream", "/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates(@PathVariable String id, HttpServletRequest request) {
        String cleanId = PasteService.sanitizeId(id);
        Optional<Paste> opt = pasteService.findById(cleanId);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(cleanId);
        }
        if (opt.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace does not exist");
        }
        Paste p = opt.get();
        if (p.isLocked() && !pasteSecurityService.isAuthorized(request, p)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workspace is password-protected. Please unlock first.");
        }
        return syncService.subscribe(p.getId());
    }

    /**
     * QR Code for Collaborative Edit Link.
     */
    @GetMapping(value = "/{id:[a-zA-Z0-9_\\-]{1,64}}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getEditQrCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "300") int size,
            @RequestParam(defaultValue = "false") boolean download,
            HttpServletRequest request
    ) {
        // If accessed with a read-only key, redirect to read-only QR
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return getReadOnlyQrCode(id, size, download, request);
        }

        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String host = resolveHost(request);
        String url = host + "/d/" + opt.get().getId();
        return renderQrResponse(url, "d-" + opt.get().getId() + "-qr.png", size, download);
    }

    /**
     * QR Code for Secure Read-Only Link.
     */
    @GetMapping(value = "/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getReadOnlyQrCode(
            @PathVariable String readOnlyKey,
            @RequestParam(defaultValue = "300") int size,
            @RequestParam(defaultValue = "false") boolean download,
            HttpServletRequest request
    ) {
        Optional<Paste> opt = pasteService.findByReadOnlyKey(readOnlyKey);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String host = resolveHost(request);
        String url = host + "/d/readonly/" + readOnlyKey;
        return renderQrResponse(url, "d-readonly-" + readOnlyKey + "-qr.png", size, download);
    }

    private String resolveHost(HttpServletRequest request) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl.replaceAll("/+$", "");
        }

        // Scheme: prioritize X-Forwarded-Proto from cloud edge / reverse proxy (Railway, Cloudflare)
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            scheme = request.getScheme();
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            scheme = "http";
        }

        // Sanitize / validate host name to prevent Host Header Injection
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || !host.matches("^[a-zA-Z0-9.\\-:]+$")) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (("http".equalsIgnoreCase(scheme) && port != 80 && port > 0) ||
                ("https".equalsIgnoreCase(scheme) && port != 443 && port > 0)) {
                host += ":" + port;
            }
        }

        // Strip default ports if present
        if ("http".equalsIgnoreCase(scheme) && host.endsWith(":80")) {
            host = host.substring(0, host.length() - 3);
        } else if ("https".equalsIgnoreCase(scheme) && host.endsWith(":443")) {
            host = host.substring(0, host.length() - 4);
        }

        // If accessed locally via localhost/127.0.0.1, fallback to LAN IP for phone Wi-Fi scanning
        if (host.startsWith("localhost") || host.startsWith("127.0.0.1")) {
            String serverLanIp = NetworkUtil.getLocalNetworkIp();
            if (serverLanIp != null && !serverLanIp.isBlank() && !serverLanIp.equals("localhost")) {
                String portPart = (serverPort == 80 || serverPort == 443 || serverPort == 0) ? "" : (":" + serverPort);
                return "http://" + serverLanIp + portPart;
            }
        }

        return scheme + "://" + host;
    }

    private ResponseEntity<byte[]> renderQrResponse(String url, String filename, int size, boolean download) {
        try {
            byte[] png = qrCodeService.generateQrCodePng(url, size, size);
            String disposition = download ? ("attachment; filename=\"" + filename + "\"") : ("inline; filename=\"" + filename + "\"");
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                    .body(png);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
