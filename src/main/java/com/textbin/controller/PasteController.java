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

    @GetMapping({"/dropbin", "/dropbin/"})
    public String dropbinLanding(Model model) {
        model.addAttribute("activePastes", pasteService.countActive());
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        return "index";
    }

    // ─── Backward compatibility & root redirects -> /dropbin ─────────────────

    @GetMapping({"/"})
    public String rootRedirect() {
        return "redirect:/dropbin";
    }

    @GetMapping({"/word", "/word/"})
    public String wordLegacyRedirect() {
        return "redirect:/dropbin";
    }

    // ─── Random Pad Redirects ─────────────────────────────────────────────────

    @GetMapping({"/dropbin/new", "/word/new", "/new"})
    public String newRandomPad() {
        return "redirect:/dropbin/" + pasteService.generateUniqueRandomId();
    }

    // ─── Form Create ──────────────────────────────────────────────────────────

    @PostMapping({"/dropbin", "/word", "/"})
    public String createFromForm(
            @RequestParam(required = false) String customUrl,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "plaintext") String syntaxLanguage
    ) {
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
    public String viewDropbinPad(@PathVariable String id, Model model) {
        Paste paste = pasteService.getOrCreate(id);
        populateModel(model, paste);
        return "view";
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

    @GetMapping(value = "/dropbin/{id:[a-zA-Z0-9_\\-\\.]+}/raw", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String rawDropbin(@PathVariable String id) {
        return pasteService.findById(id)
                .map(Paste::getContent)
                .orElse("");
    }

    @GetMapping(value = "/word/{id:[a-zA-Z0-9_\\-\\.]+}/raw", produces = "text/plain;charset=UTF-8")
    public String legacyWordRawRedirect(@PathVariable String id) {
        return "redirect:/dropbin/" + id + "/raw";
    }

    @GetMapping(value = "/raw/{id:[a-zA-Z0-9_\\-\\.]+}", produces = "text/plain;charset=UTF-8")
    public String legacyRawRedirect(@PathVariable String id) {
        return "redirect:/dropbin/" + id + "/raw";
    }

    // ─── Helper to populate common model attributes ───────────────────────────

    private void populateModel(Model model, Paste paste) {
        model.addAttribute("paste", paste);
        model.addAttribute("serverLanIp", NetworkUtil.getLocalNetworkIp());
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("secondsLeft", paste.secondsUntilExpiry());

        List<BinFileDto> files = fileStorageService.getFilesForPaste(paste.getId());
        model.addAttribute("attachedFiles", files);
    }

    // ─── 404 handler ──────────────────────────────────────────────────────────

    @ExceptionHandler(ResponseStatusException.class)
    public String handleNotFound(ResponseStatusException ex, Model model) {
        model.addAttribute("message", ex.getReason());
        return "error";
    }
}
