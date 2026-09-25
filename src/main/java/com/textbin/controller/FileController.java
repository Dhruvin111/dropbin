package com.textbin.controller;

import com.textbin.dto.BinFileDto;
import com.textbin.model.BinFile;
import com.textbin.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

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

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    // ─── 1. Upload File (up to 10MB; Image, PDF, Word) ───────────────────────
    @PostMapping(
            value = {"/api/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files", "/api/pastes/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> uploadFile(
            @PathVariable String pasteId,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            BinFileDto dto = fileStorageService.storeFile(pasteId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException | SecurityException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    // ─── 2. List Files for Pad ────────────────────────────────────────────────
    @GetMapping({"/api/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files", "/api/pastes/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files"})
    public ResponseEntity<List<BinFileDto>> listFiles(@PathVariable String pasteId) {
        List<BinFileDto> files = fileStorageService.getFilesForPaste(pasteId);
        return ResponseEntity.ok(files);
    }

    // ─── 3. Delete File ───────────────────────────────────────────────────────
    @DeleteMapping({
            "/api/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}",
            "/api/pastes/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}"
    })
    public ResponseEntity<?> deleteFile(
            @PathVariable String pasteId,
            @PathVariable String fileId
    ) {
        boolean deleted = fileStorageService.deleteFile(pasteId, fileId);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found or already deleted"));
    }

    // ─── 4. View File Inline (Images, PDF) ────────────────────────────────────
    @GetMapping({
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/view",
            "/word/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/view"
    })
    public ResponseEntity<Resource> viewFile(
            @PathVariable String pasteId,
            @PathVariable String fileId
    ) {
        return serveFile(pasteId, fileId, false);
    }

    // ─── 5. Download File Attachment (Word, PDF, Images) ──────────────────────
    @GetMapping({
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/download",
            "/word/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/download"
    })
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String pasteId,
            @PathVariable String fileId
    ) {
        return serveFile(pasteId, fileId, true);
    }

    // ─── 6. Friendly File URL with original filename ─────────────────────────
    @GetMapping({
            "/dropbin/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/{filename:.+}",
            "/word/{pasteId:[a-zA-Z0-9_\\-\\.]+}/files/{fileId:[a-zA-Z0-9_\\-\\.]+}/{filename:.+}"
    })
    public ResponseEntity<Resource> getNamedFile(
            @PathVariable String pasteId,
            @PathVariable String fileId,
            @PathVariable String filename,
            @RequestParam(value = "download", defaultValue = "false") boolean download
    ) {
        return serveFile(pasteId, fileId, download);
    }

    private ResponseEntity<Resource> serveFile(String pasteId, String fileId, boolean asAttachment) {
        Optional<BinFile> opt = fileStorageService.findById(fileId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        BinFile bf = opt.get();
        if (!bf.getPasteId().equalsIgnoreCase(pasteId)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = Paths.get(bf.getStoragePath());
            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists() || !resource.isReadable()) {
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
            String dispositionType = asAttachment ? "attachment" : "inline";
            String contentDisposition = dispositionType + "; filename=\"" + bf.getOriginalFilename().replace("\"", "\\\"") + "\"; filename*=UTF-8''" + encodedFilename;

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(bf.getFileSize())
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                    .body(resource);

        } catch (MalformedURLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Uploaded file exceeds the maximum allowed size of 10MB."));
    }
}
