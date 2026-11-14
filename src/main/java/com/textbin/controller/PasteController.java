package com.textbin.controller;

import com.textbin.dto.BinFileDto;
import com.textbin.model.Paste;
import com.textbin.service.FileStorageService;
import com.textbin.service.PasteSecurityService;
import com.textbin.service.PasteService;
import com.textbin.service.PasteSyncService;
import com.textbin.util.NetworkUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Controller
public class PasteController {

    private final PasteService pasteService;
    private final FileStorageService fileStorageService;
    private final PasteSyncService syncService;
    private final PasteSecurityService pasteSecurityService;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${app.mode:auto}")
    private String appMode;

    public PasteController(PasteService pasteService, FileStorageService fileStorageService,
                           PasteSyncService syncService, PasteSecurityService pasteSecurityService) {
        this.pasteService = pasteService;
        this.fileStorageService = fileStorageService;
        this.syncService = syncService;
        this.pasteSecurityService = pasteSecurityService;
    }

    // ─── Landing Page at / or /d ─────────────────────────────────────────────

    @GetMapping({ "/", "/d", "/d/", "/dropbin", "/dropbin/" })
    public String dropbinLanding(Model model) {
        model.addAttribute("activePastes", pasteService.countActive());
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("isLiveMode", isLiveEnvironment());
        return "index";
    }

    // ─── Backward compatibility redirects ─────────────────────────────────────

    @GetMapping({ "/word", "/word/" })
    public String wordLegacyRedirect() {
        return "redirect:/";
    }

    // ─── Random Pad Redirects ─────────────────────────────────────────────────

    @GetMapping({ "/d/new", "/dropbin/new", "/word/new", "/new" })
    public String newRandomPad() {
        return "redirect:/d/" + pasteService.generateUniqueRandomId();
    }

    // ─── Form Create ──────────────────────────────────────────────────────────

    @PostMapping({ "/d", "/dropbin", "/word", "/" })
    public String createFromForm(
            @RequestParam(required = false) String customUrl,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "plaintext") String syntaxLanguage) {
        String targetId = (customUrl != null && !customUrl.isBlank())
                ? PasteService.sanitizeId(customUrl)
                : pasteService.generateUniqueRandomId();

        if (pasteService.isReadOnlyKey(targetId) || targetId.startsWith("ro_")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Cannot create or edit workspace with a read-only key");
        }

        if (content != null && !content.isBlank()) {
            pasteService.saveOrUpdate(targetId, content, title, syntaxLanguage, "form-init");
        } else {
            pasteService.getOrCreate(targetId);
        }

        return "redirect:/d/" + targetId;
    }

    // ─── Takedown / Delete Bin Form Fallback ──────────────────────────────────

    @PostMapping({ "/d/{id:[a-zA-Z0-9_\\-]{1,64}}/takedown", "/dropbin/{id:[a-zA-Z0-9_\\-]{1,64}}/takedown", "/word/{id:[a-zA-Z0-9_\\-]{1,64}}/takedown" })
    public String takedownFromForm(@PathVariable String id, HttpServletRequest request) {
        if (pasteService.isReadOnlyKey(id) || id.startsWith("ro_")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Cannot delete in read-only mode");
        }
        String cleanId = PasteService.sanitizeId(id);
        Paste paste = pasteService.findById(cleanId).orElse(null);
        if (paste != null && paste.isLocked() && !pasteSecurityService.isAuthorized(request, paste)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Workspace is locked. Enter password first.");
        }
        try {
            fileStorageService.deleteAllFilesForPaste(cleanId);
        } catch (Exception ignored) {}
        try {
            syncService.broadcastEvent(cleanId, "bin_deleted", java.util.Map.of("pasteId", cleanId));
        } catch (Exception ignored) {}
        try {
            syncService.closeEmitters(cleanId);
        } catch (Exception ignored) {}
        try {
            pasteService.deletePaste(cleanId);
        } catch (Exception ignored) {}
        return "redirect:/?deleted=" + cleanId;
    }

    // ─── Pad View / Edit: Primary route is /d/{id} ─────────────────────────────

    @GetMapping("/d/{id:[a-zA-Z0-9_\\-]{1,64}}")
    public String viewDropbinPad(
            @PathVariable String id,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "readonly", required = false) Boolean readonlyParam,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        // If the ID passed is actually a secret read-only key, keep them permanently in
        // read-only mode!
        if (pasteService.isReadOnlyKey(id)) {
            return "redirect:/d/readonly/" + id;
        }

        boolean isReadOnly = "readonly".equalsIgnoreCase(mode) || Boolean.TRUE.equals(readonlyParam);

        Paste paste = pasteService.getOrCreate(id);

        if (paste.isLocked() && !pasteSecurityService.isAuthorized(request, paste)) {
            populateLockModel(model, paste, id, false);
            return "lock";
        }

        populateModel(model, paste, isReadOnly, paste.getReadOnlyKey());
        model.addAttribute("isReadOnly", isReadOnly);
        model.addAttribute("readOnlyKey", paste.getReadOnlyKey());
        model.addAttribute("activeId", isReadOnly ? ("readonly/" + paste.getReadOnlyKey()) : paste.getId());
        model.addAttribute("isLocked", paste.isLocked());

        String authToken = "";
        if (paste.isLocked()) {
            authToken = pasteSecurityService.extractToken(request, paste.getId());
            if (authToken == null || !pasteSecurityService.validateToken(authToken, paste)) {
                authToken = pasteSecurityService.generateToken(paste);
                boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
                pasteSecurityService.setAuthCookie(response, paste, authToken, isSecure);
            }
        }
        model.addAttribute("authToken", authToken);

        return "view";
    }

    // ─── Dedicated Secure Read-Only Token Route ──────────────────────────────

    @GetMapping({ "/d/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}",
            "/d/r/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}",
            "/dropbin/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}",
            "/word/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}" })
    public String viewDropbinByReadOnlyKey(
            @PathVariable String readOnlyKey,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Paste paste = pasteService.findByReadOnlyKey(readOnlyKey)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "This read-only paste has expired or never existed."));

        if (paste.isLocked() && !pasteSecurityService.isAuthorized(request, paste)) {
            populateLockModel(model, paste, "readonly/" + readOnlyKey, true);
            return "lock";
        }

        populateModel(model, paste, true, readOnlyKey);
        model.addAttribute("isReadOnly", true);
        model.addAttribute("readOnlyKey", readOnlyKey);
        // Do NOT expose the secret edit ID to the read-only viewer!
        model.addAttribute("activeId", "readonly/" + readOnlyKey);
        model.addAttribute("isLocked", paste.isLocked());

        String authToken = "";
        if (paste.isLocked()) {
            authToken = pasteSecurityService.extractToken(request, paste.getId());
            if (authToken == null || !pasteSecurityService.validateToken(authToken, paste)) {
                authToken = pasteSecurityService.generateToken(paste);
                boolean isSecure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
                pasteSecurityService.setAuthCookie(response, paste, authToken, isSecure);
            }
        }
        model.addAttribute("authToken", authToken);

        return "view";
    }

    // ─── Legacy /d/{id}/readonly & /dropbin/{id}/readonly -> Redirect to secure token route ─────

    @GetMapping({ "/d/{id:[a-zA-Z0-9_\\-]{1,64}}/readonly",
            "/dropbin/{id:[a-zA-Z0-9_\\-]{1,64}}/readonly",
            "/word/{id:[a-zA-Z0-9_\\-]{1,64}}/readonly" })
    public String viewDropbinPadReadOnlyRedirect(@PathVariable String id) {
        if (pasteService.isReadOnlyKey(id)) {
            return "redirect:/d/readonly/" + id;
        }
        Paste paste = pasteService.getOrCreate(id);
        return "redirect:/d/readonly/" + paste.getReadOnlyKey();
    }

    // ─── Legacy /dropbin/{id} and /word/{id} Redirect to /d/{id} ──────────────

    @GetMapping({ "/dropbin/{id:[a-zA-Z0-9_\\-]{1,64}}", "/word/{id:[a-zA-Z0-9_\\-]{1,64}}" })
    public String redirectLegacyToPad(@PathVariable String id) {
        return "redirect:/d/" + id;
    }

    // ─── Direct URL without prefix -> Enforce redirect to /d/{id} ────────────

    @GetMapping("/{id:^(?!api$|raw$|dropbin$|word$|d$|new$|error$|static$|robots\\.txt$|sitemap\\.xml$|favicon\\.ico$|.*\\.(?:css|js|ico|png|jpg|jpeg|svg|map|txt|xml|json)$)[a-zA-Z0-9_\\-]{1,64}$}")
    public String redirectDirectToDropbin(@PathVariable String id) {
        return "redirect:/d/" + id;
    }

    // ─── Raw text endpoints ───────────────────────────────────────────────────

    @GetMapping(value = { "/d/{id:[a-zA-Z0-9_\\-]{1,64}}/raw",
            "/d/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/raw",
            "/d/r/{id:[a-zA-Z0-9_\\-]{1,64}}/raw",
            "/dropbin/{id:[a-zA-Z0-9_\\-]{1,64}}/raw",
            "/dropbin/readonly/{id:[a-zA-Z0-9_\\-]{1,64}}/raw" }, produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> rawDropbin(@PathVariable String id, HttpServletRequest request) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        if (opt.isEmpty()) {
            return ResponseEntity.ok("");
        }
        Paste paste = opt.get();
        if (paste.isLocked() && !pasteSecurityService.isAuthorized(request, paste)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body("This workspace is password-protected. Please unlock via web browser at /d/" + paste.getId());
        }
        return ResponseEntity.ok(paste.getContent() != null ? paste.getContent() : "");
    }

    @GetMapping(value = { "/word/{id:[a-zA-Z0-9_\\-]{1,64}}/raw", "/raw/{id:[a-zA-Z0-9_\\-]{1,64}}" }, produces = "text/plain;charset=UTF-8")
    public String legacyWordRawRedirect(@PathVariable String id) {
        return "redirect:/d/" + id + "/raw";
    }

    // ─── Direct QR routes forwarding to API ───────────────────────────────────

    @GetMapping(value = { "/d/{id:[a-zA-Z0-9_\\-]{1,64}}/qr", "/dropbin/{id:[a-zA-Z0-9_\\-]{1,64}}/qr" })
    public String directDropbinQr(@PathVariable String id) {
        return "forward:/api/d/" + id + "/qr";
    }

    @GetMapping(value = { "/d/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}/qr", "/dropbin/readonly/{readOnlyKey:[a-zA-Z0-9_\\-]{1,64}}/qr" })
    public String directDropbinReadOnlyQr(@PathVariable String readOnlyKey) {
        return "forward:/api/d/readonly/" + readOnlyKey + "/qr";
    }

    // ─── Helper to populate common model attributes ───────────────────────────

    private void populateModel(Model model, Paste paste, boolean isReadOnly, String readOnlyKey) {
        model.addAttribute("paste", paste);
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("secondsLeft", paste.secondsUntilExpiry());
        model.addAttribute("isLiveMode", isLiveEnvironment());
        model.addAttribute("isLocked", paste.isLocked());

        String filePrefix = (isReadOnly && readOnlyKey != null && !readOnlyKey.isBlank()) ? ("readonly/" + readOnlyKey)
                : null;
        List<BinFileDto> files = fileStorageService.getFilesForPaste(paste.getId(), filePrefix);
        model.addAttribute("attachedFiles", files);
    }

    private void populateLockModel(Model model, Paste paste, String targetId, boolean isReadOnly) {
        model.addAttribute("pasteId", isReadOnly ? paste.getReadOnlyKey() : paste.getId());
        model.addAttribute("activeId", targetId);
        model.addAttribute("title", paste.getTitle());
        model.addAttribute("isReadOnly", isReadOnly);
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("isLiveMode", isLiveEnvironment());
    }

    private boolean isLiveEnvironment() {
        if ("live".equalsIgnoreCase(appMode)) {
            return true;
        }
        if ("lan".equalsIgnoreCase(appMode) || "local".equalsIgnoreCase(appMode)) {
            return false;
        }
        // Auto-detect based on cloud platform environment variables
        return System.getenv("DATABASE_URL") != null
                || System.getenv("RENDER") != null
                || System.getenv("RAILWAY_ENVIRONMENT") != null
                || System.getenv("FLY_APP_NAME") != null
                || System.getenv("KOYEB_APP_NAME") != null;
    }

    // ─── 404 handler ──────────────────────────────────────────────────────────

    @ExceptionHandler(ResponseStatusException.class)
    public String handleNotFound(ResponseStatusException ex, Model model) {
        model.addAttribute("message", ex.getReason());
        return "error";
    }
}
