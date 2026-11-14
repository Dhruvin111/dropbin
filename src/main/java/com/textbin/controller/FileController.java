package com.textbin.controller;

import com.textbin.dto.BinFileDto;
import com.textbin.model.BinFile;
import com.textbin.service.FileStorageService;
import com.textbin.service.PasteService;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.textbin.model.Paste;
import com.textbin.service.PasteSecurityService;
import jakarta.servlet.http.HttpServletRequest;

import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class FileController {

    private final FileStorageService fileStorageService;
    private final PasteService pasteService;
    private final PasteSecurityService pasteSecurityService;

    public FileController(FileStorageService fileStorageService,
                          PasteService pasteService,
                          PasteSecurityService pasteSecurityService) {
        this.fileStorageService = fileStorageService;
        this.pasteService = pasteService;
        this.pasteSecurityService = pasteSecurityService;
    }

    // ─── 1. Upload File (up to 10MB; Image, PDF, Word) ───────────────────────
    @PostMapping(
            value = {
                    "/api/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
                    "/api/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
                    "/api/pastes/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files"
            },
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> uploadFile(
            @PathVariable String pasteId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request
    ) {
        if (pasteService.isReadOnlyKey(pasteId) || pasteId.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot upload files to a read-only bin."));
        }
        Optional<Paste> opt = pasteService.findById(pasteId);
        if (opt.isPresent() && opt.get().isLocked() && !pasteSecurityService.isAuthorized(request, opt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Workspace is locked. Enter password to upload files."));
        }
        try {
            BinFileDto dto = fileStorageService.storeFile(pasteId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    // ─── 2. List Files for Pad ────────────────────────────────────────────────
    @GetMapping({
            "/api/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/d/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/dropbin/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/pastes/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files"
    })
    public ResponseEntity<List<BinFileDto>> listFiles(@PathVariable String pasteId, HttpServletRequest request) {
        boolean isRo = pasteService.isReadOnlyKey(pasteId);
        String resolvedId = isRo
                ? pasteService.findByReadOnlyKey(pasteId).map(com.textbin.model.Paste::getId).orElse(pasteId)
                : pasteId;

        Optional<Paste> opt = pasteService.findById(resolvedId);
        if (opt.isPresent() && opt.get().isLocked() && !pasteSecurityService.isAuthorized(request, opt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String routePrefix = isRo ? ("readonly/" + pasteId) : null;
        List<BinFileDto> files = fileStorageService.getFilesForPaste(resolvedId, routePrefix);
        return ResponseEntity.ok(files);
    }

    // ─── 3. Delete File ───────────────────────────────────────────────────────
    @DeleteMapping({
            "/api/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}",
            "/api/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}",
            "/api/pastes/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}"
    })
    public ResponseEntity<?> deleteFile(
            @PathVariable String pasteId,
            @PathVariable String fileId,
            HttpServletRequest request
    ) {
        if (pasteService.isReadOnlyKey(pasteId) || pasteId.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot delete files from a read-only bin."));
        }
        Optional<Paste> opt = pasteService.findById(pasteId);
        if (opt.isPresent() && opt.get().isLocked() && !pasteSecurityService.isAuthorized(request, opt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Workspace is locked. Enter password first."));
        }
        boolean deleted = fileStorageService.deleteFile(pasteId, fileId);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found or already deleted"));
    }

    // ─── 3b. Delete All Files for Bin ──────────────────────────────────────────
    @DeleteMapping({
            "/api/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files",
            "/api/pastes/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files"
    })
    public ResponseEntity<?> deleteAllFiles(@PathVariable String pasteId, HttpServletRequest request) {
        if (pasteService.isReadOnlyKey(pasteId) || pasteId.startsWith("ro_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot delete files from a read-only bin."));
        }
        Optional<Paste> opt = pasteService.findById(pasteId);
        if (opt.isPresent() && opt.get().isLocked() && !pasteSecurityService.isAuthorized(request, opt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Workspace is locked. Enter password first."));
        }
        fileStorageService.deleteAllFilesForPaste(pasteId);
        return ResponseEntity.noContent().build();
    }

    // ─── 4. View File Inline (Images, PDF) ────────────────────────────────────
    @GetMapping({
            "/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view",
            "/d/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view",
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view",
            "/dropbin/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view",
            "/word/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view",
            "/word/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/view"
    })
    public ResponseEntity<Resource> viewFile(
            @PathVariable String pasteId,
            @PathVariable String fileId,
            HttpServletRequest request
    ) {
        return serveFile(pasteId, fileId, false, request);
    }

    // ─── 5. Download File Attachment (Word, PDF, Images) ──────────────────────
    @GetMapping({
            "/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download",
            "/d/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download",
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download",
            "/dropbin/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download",
            "/word/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download",
            "/word/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/download"
    })
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String pasteId,
            @PathVariable String fileId,
            HttpServletRequest request
    ) {
        return serveFile(pasteId, fileId, true, request);
    }

    // ─── 6. Friendly File URL with original filename ─────────────────────────
    @GetMapping({
            "/d/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}",
            "/d/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}",
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}",
            "/dropbin/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}",
            "/word/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}",
            "/word/readonly/{pasteId:[a-zA-Z0-9_\\-]{1,64}}/files/{fileId:[a-zA-Z0-9_\\-]{1,64}}/{filename:[a-zA-Z0-9._\\-]{1,255}}"
    })
    public ResponseEntity<Resource> getNamedFile(
            @PathVariable String pasteId,
            @PathVariable String fileId,
            @PathVariable String filename,
            @RequestParam(value = "download", defaultValue = "false") boolean download,
            HttpServletRequest request
    ) {
        return serveFile(pasteId, fileId, download, request);
    }

    private ResponseEntity<Resource> serveFile(String pasteId, String fileId, boolean asAttachment, HttpServletRequest request) {
        String resolvedId = pasteService.findByReadOnlyKey(pasteId)
                .map(com.textbin.model.Paste::getId)
                .orElse(pasteId);

        Optional<Paste> optPaste = pasteService.findById(resolvedId);
        if (optPaste.isPresent() && optPaste.get().isLocked() && !pasteSecurityService.isAuthorized(request, optPaste.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<BinFile> opt = fileStorageService.findById(fileId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        BinFile bf = opt.get();
        if (!bf.getPasteId().equalsIgnoreCase(resolvedId)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Resource resource = fileStorageService.loadFileAsResource(bf);

            if (resource == null || !resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(bf.getContentType());
            } catch (Exception e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }

            String encodedFilename = URLEncoder.encode(bf.getOriginalFilename(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            // Word documents must always be forced as attachment; images and pdf can be viewed inline
            boolean forceAttachment = asAttachment || "WORD".equalsIgnoreCase(bf.getFileCategory());
            String dispositionType = forceAttachment ? "attachment" : "inline";
            String safeHeaderFilename = bf.getOriginalFilename().replaceAll("[\\r\\n\"]", "_");
            String contentDisposition = dispositionType + "; filename=\"" + safeHeaderFilename + "\"; filename*=UTF-8''" + encodedFilename;

            var responseBuilder = ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(bf.getFileSize())
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY")
                    // Enforce restrictive CSP on ALL served uploads to prevent Stored XSS
                    .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");

            return responseBuilder.body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Uploaded file exceeds the maximum allowed size of 10MB."));
    }
}
