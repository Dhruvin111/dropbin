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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;

@RestController
@RequestMapping({"/api/dropbin", "/api/pastes"})
public class PasteApiController {

    private final PasteService pasteService;
    private final PasteSyncService syncService;
    private final QrCodeService qrCodeService;
    private final FileStorageService fileStorageService;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${app.base-url:}")
    private String configuredBaseUrl;

    public PasteApiController(PasteService pasteService, PasteSyncService syncService, QrCodeService qrCodeService, FileStorageService fileStorageService) {
        this.pasteService = pasteService;
        this.syncService = syncService;
        this.qrCodeService = qrCodeService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Auto-save / debounce persistence endpoint.
     */
    @PostMapping("/{id:[a-zA-Z0-9_\\-\\.]+}")
    public ResponseEntity<?> savePaste(
            @PathVariable String id,
            @RequestBody SavePasteRequest request
    ) {
        // Enforce server-side security: Reject edits if accessed with a read-only key
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "This bin is being accessed in read-only mode and cannot be edited."));
        }

        Paste saved = pasteService.saveOrUpdate(
                id,
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
                request.getClientToken()
        );
        return ResponseEntity.ok(resp);
    }

    /**
     * Takedown / Delete bin immediately (wiping text and all attached files).
     */
    @DeleteMapping({"/{id:[a-zA-Z0-9_\\-\\.]+}", "/readonly/{id:[a-zA-Z0-9_\\-\\.]+}"})
    public ResponseEntity<?> takedownPasteByDelete(@PathVariable String id) {
        return processTakedown(id);
    }

    /**
     * Fallback endpoint for environments where DELETE is restricted or converted to POST.
     */
    @PostMapping({"/{id:[a-zA-Z0-9_\\-\\.]+}/takedown", "/readonly/{id:[a-zA-Z0-9_\\-\\.]+}/takedown"})
    public ResponseEntity<?> takedownPasteByPost(@PathVariable String id) {
        return processTakedown(id);
    }

    private ResponseEntity<?> processTakedown(String id) {
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "Cannot take down a bin in read-only mode."));
        }

        String cleanId = PasteService.sanitizeId(id);

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

        // 3. Delete the paste from database
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
     * Lightweight status check (version, timestamp, length) for polling sync.
     */
    @GetMapping({"/{id:[a-zA-Z0-9_\\-\\.]+}/status", "/readonly/{id:[a-zA-Z0-9_\\-\\.]+}/status"})
    public ResponseEntity<PasteStatusDto> getStatus(@PathVariable String id) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Paste p = opt.get();
        int len = p.getContent() != null ? p.getContent().length() : 0;
        return ResponseEntity.ok(new PasteStatusDto(p.getId(), p.getVersion(), p.getUpdatedAt(), len));
    }

    /**
     * Full details for a paste.
     */
    @GetMapping({"/{id:[a-zA-Z0-9_\\-\\.]+}", "/readonly/{id:[a-zA-Z0-9_\\-\\.]+}"})
    public ResponseEntity<PasteResponse> getPaste(@PathVariable String id) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        return opt
                .map(p -> ResponseEntity.ok(new PasteResponse(
                        p.getId(),
                        p.getContent(),
                        p.getTitle(),
                        p.getSyntaxLanguage(),
                        p.getVersion(),
                        p.getUpdatedAt(),
                        null
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Server-Sent Events stream for instant real-time push to other devices.
     */
    @GetMapping(value = {"/{id:[a-zA-Z0-9_\\-\\.]+}/stream", "/readonly/{id:[a-zA-Z0-9_\\-\\.]+}/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates(@PathVariable String id) {
        String cleanId = PasteService.sanitizeId(id);
        Optional<Paste> roPaste = pasteService.findByReadOnlyKey(cleanId);
        if (roPaste.isPresent()) {
            return syncService.subscribe(roPaste.get().getId());
        }
        return syncService.subscribe(cleanId);
    }

    /**
     * QR Code for Collaborative Edit Link.
     */
    @GetMapping(value = "/{id:[a-zA-Z0-9_\\-\\.]+}/qr", produces = MediaType.IMAGE_PNG_VALUE)
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
        String url = host + "/dropbin/" + opt.get().getId();
        return renderQrResponse(url, "dropbin-" + opt.get().getId() + "-qr.png", size, download);
    }

    /**
     * QR Code for Secure Read-Only Link.
     */
    @GetMapping(value = "/readonly/{readOnlyKey:[a-zA-Z0-9_\\-\\.]+}/qr", produces = MediaType.IMAGE_PNG_VALUE)
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
        String url = host + "/dropbin/readonly/" + readOnlyKey;
        return renderQrResponse(url, "dropbin-readonly-" + readOnlyKey + "-qr.png", size, download);
    }

    private String resolveHost(HttpServletRequest request) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl.replaceAll("/+$", "");
        }

        // Scheme: prioritize X-Forwarded-Proto from cloud edge / reverse proxy (Railway, Cloudflare)
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }

        // Host: prioritize X-Forwarded-Host, then Host header, then request.getServerName()
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
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
