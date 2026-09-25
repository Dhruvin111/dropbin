package com.textbin.controller;

import com.textbin.dto.BinFileDto;
import com.textbin.model.Paste;
import com.textbin.service.FileStorageService;
import com.textbin.service.PasteService;
import com.textbin.util.NetworkUtil;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${server.port:8080}")
    private int serverPort;

    public PasteController(PasteService pasteService, FileStorageService fileStorageService) {
        this.pasteService = pasteService;
        this.fileStorageService = fileStorageService;
    }

    // ─── Landing Page at /dropbin ─────────────────────────────────────────────

    @GetMapping({ "/dropbin", "/dropbin/" })
    public String dropbinLanding(Model model) {
        model.addAttribute("activePastes", pasteService.countActive());
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        return "index";
    }

    // ─── Backward compatibility & root redirects -> /dropbin ─────────────────

    @GetMapping({ "/" })
    public String rootRedirect() {
        return "redirect:/dropbin";
    }

    @GetMapping({ "/word", "/word/" })
    public String wordLegacyRedirect() {
        return "redirect:/dropbin";
    }

    // ─── Random Pad Redirects ─────────────────────────────────────────────────

    @GetMapping({ "/dropbin/new", "/word/new", "/new" })
    public String newRandomPad() {
        return "redirect:/dropbin/" + pasteService.generateUniqueRandomId();
    }

    // ─── Form Create ──────────────────────────────────────────────────────────

    @PostMapping({ "/dropbin", "/word", "/" })
    public String createFromForm(
            @RequestParam(required = false) String customUrl,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "plaintext") String syntaxLanguage) {
        String targetId = (customUrl != null && !customUrl.isBlank())
                ? PasteService.sanitizeId(customUrl)
                : pasteService.generateUniqueRandomId();

        if (content != null && !content.isBlank()) {
            pasteService.saveOrUpdate(targetId, content, title, syntaxLanguage, "form-init");
        } else {
            pasteService.getOrCreate(targetId);
        }

        return "redirect:/dropbin/" + targetId;
    }

    // ─── Pad View / Edit: Primary route is /dropbin/{id} ─────────────────────

    @GetMapping("/dropbin/{id:[a-zA-Z0-9_\\-\\.]+}")
    public String viewDropbinPad(
            @PathVariable String id,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "readonly", required = false) Boolean readonlyParam,
            Model model) {
        // If the ID passed is actually a secret read-only key, keep them permanently in
        // read-only mode!
        if (pasteService.isReadOnlyKey(id)) {
            return "redirect:/dropbin/readonly/" + id;
        }

        boolean isReadOnly = "readonly".equalsIgnoreCase(mode) || Boolean.TRUE.equals(readonlyParam);

        Paste paste = pasteService.getOrCreate(id);
        populateModel(model, paste, isReadOnly, paste.getReadOnlyKey());
        model.addAttribute("isReadOnly", isReadOnly);
        model.addAttribute("readOnlyKey", paste.getReadOnlyKey());
        model.addAttribute("activeId", isReadOnly ? ("readonly/" + paste.getReadOnlyKey()) : paste.getId());
        return "view";
    }

    // ─── Dedicated Secure Read-Only Token Route ──────────────────────────────

    @GetMapping({ "/dropbin/readonly/{readOnlyKey:[a-zA-Z0-9_\\-\\.]+}",
            "/word/readonly/{readOnlyKey:[a-zA-Z0-9_\\-\\.]+}" })
    public String viewDropbinByReadOnlyKey(@PathVariable String readOnlyKey, Model model) {
        Paste paste = pasteService.findByReadOnlyKey(readOnlyKey)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "This read-only paste has expired or never existed."));

        populateModel(model, paste, true, readOnlyKey);
        model.addAttribute("isReadOnly", true);
        model.addAttribute("readOnlyKey", readOnlyKey);
        // Do NOT expose the secret edit ID to the read-only viewer!
        model.addAttribute("activeId", "readonly/" + readOnlyKey);
        return "view";
    }

    // ─── Legacy /dropbin/{id}/readonly -> Redirect to secure token route ─────

    @GetMapping("/dropbin/{id:[a-zA-Z0-9_\\-\\.]+}/readonly")
    public String viewDropbinPadReadOnlyRedirect(@PathVariable String id) {
        if (pasteService.isReadOnlyKey(id)) {
            return "redirect:/dropbin/readonly/" + id;
        }
        Paste paste = pasteService.getOrCreate(id);
        return "redirect:/dropbin/readonly/" + paste.getReadOnlyKey();
    }

    @GetMapping("/word/{id:[a-zA-Z0-9_\\-\\.]+}/readonly")
    public String redirectWordReadOnlyToDropbin(@PathVariable String id) {
        return viewDropbinPadReadOnlyRedirect(id);
    }

    // ─── Legacy /word/{id} Redirect to /dropbin/{id} ──────────────────────────

    @GetMapping("/word/{id:[a-zA-Z0-9_\\-\\.]+}")
    public String redirectWordToDropbin(@PathVariable String id) {
        return "redirect:/dropbin/" + id;
    }

    // ─── Direct URL without prefix -> Enforce redirect to /dropbin/{id} ───────

    @GetMapping("/{id:^(?!api$|raw$|dropbin$|word$|new$|error$|static$|.*\\.(?:css|js|ico|png|jpg|jpeg|svg|map)$)[a-zA-Z0-9_\\-\\.]+$}")
    public String redirectDirectToDropbin(@PathVariable String id) {
        return "redirect:/dropbin/" + id;
    }

    // ─── Raw text endpoints ───────────────────────────────────────────────────

    @GetMapping(value = { "/dropbin/{id:[a-zA-Z0-9_\\-\\.]+}/raw",
            "/dropbin/readonly/{id:[a-zA-Z0-9_\\-\\.]+}/raw" }, produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String rawDropbin(@PathVariable String id) {
        Optional<Paste> opt = pasteService.findById(id);
        if (opt.isEmpty()) {
            opt = pasteService.findByReadOnlyKey(id);
        }
        return opt.map(Paste::getContent).orElse("");
    }

    @GetMapping(value = "/word/{id:[a-zA-Z0-9_\\-\\.]+}/raw", produces = "text/plain;charset=UTF-8")
    public String legacyWordRawRedirect(@PathVariable String id) {
        return "redirect:/dropbin/" + id + "/raw";
    }

    @GetMapping(value = "/raw/{id:[a-zA-Z0-9_\\-\\.]+}", produces = "text/plain;charset=UTF-8")
    public String legacyRawRedirect(@PathVariable String id) {
        return "redirect:/dropbin/" + id + "/raw";
    }

    // ─── Direct QR routes forwarding to API ───────────────────────────────────

    @GetMapping(value = "/dropbin/{id:[a-zA-Z0-9_\\-\\.]+}/qr")
    public String directDropbinQr(@PathVariable String id) {
        return "forward:/api/dropbin/" + id + "/qr";
    }

    @GetMapping(value = "/dropbin/readonly/{readOnlyKey:[a-zA-Z0-9_\\-\\.]+}/qr")
    public String directDropbinReadOnlyQr(@PathVariable String readOnlyKey) {
        return "forward:/api/dropbin/readonly/" + readOnlyKey + "/qr";
    }

    // ─── Helper to populate common model attributes ───────────────────────────

    private void populateModel(Model model, Paste paste, boolean isReadOnly, String readOnlyKey) {
        model.addAttribute("paste", paste);
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("secondsLeft", paste.secondsUntilExpiry());

        String filePrefix = (isReadOnly && readOnlyKey != null && !readOnlyKey.isBlank()) ? ("readonly/" + readOnlyKey)
                : null;
        List<BinFileDto> files = fileStorageService.getFilesForPaste(paste.getId(), filePrefix);
        model.addAttribute("attachedFiles", files);
    }

    // ─── 404 handler ──────────────────────────────────────────────────────────

    @ExceptionHandler(ResponseStatusException.class)
    public String handleNotFound(ResponseStatusException ex, Model model) {
        model.addAttribute("message", ex.getReason());
        return "error";
    }
}
