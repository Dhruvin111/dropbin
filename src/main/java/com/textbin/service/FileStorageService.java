package com.textbin.service;

import com.textbin.dto.BinFileDto;
import com.textbin.model.BinFile;
import com.textbin.repository.BinFileRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10MB

    private final BinFileRepository fileRepo;
    private final PasteService pasteService;
    private final PasteSyncService syncService;

    @Value("${app.upload.dir:./uploads}")
    private String uploadBaseDir;

    private Path rootStoragePath;

    public FileStorageService(BinFileRepository fileRepo, PasteService pasteService, PasteSyncService syncService) {
        this.fileRepo = fileRepo;
        this.pasteService = pasteService;
        this.syncService = syncService;
    }

    @PostConstruct
    public void init() {
        this.rootStoragePath = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootStoragePath);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload directory: " + this.rootStoragePath, e);
        }
    }

    @Transactional
    public BinFileDto storeFile(String rawPasteId, MultipartFile file) throws IOException {
        String pasteId = PasteService.sanitizeId(rawPasteId);

        // Ensure paste exists or is created
        pasteService.getOrCreate(pasteId);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload an empty file.");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size exceeds the 10MB limit (" + (file.getSize() / (1024 * 1024)) + "MB).");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "upload_" + System.currentTimeMillis();
        } else {
            originalFilename = Paths.get(originalFilename).getFileName().toString();
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        String category = determineCategory(originalFilename, contentType);
        if (category == null) {
            throw new IllegalArgumentException("Unsupported file type. Only Image, PDF, and Word (.doc, .docx) files are supported.");
        }

        // Generate unique file id
        String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Storage directory: ./uploads/{pasteId}/{fileId}/
        Path targetDir = this.rootStoragePath.resolve(pasteId).resolve(fileId).normalize();
        Files.createDirectories(targetDir);

        Path targetFilePath = targetDir.resolve(originalFilename).normalize();

        // Safety check against directory traversal
        if (!targetFilePath.startsWith(this.rootStoragePath)) {
            throw new SecurityException("Invalid file path resolution.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
        }

        BinFile binFile = new BinFile(
                fileId,
                pasteId,
                originalFilename,
                contentType,
                file.getSize(),
                targetFilePath.toString(),
                category,
                Instant.now()
        );

        BinFile saved = fileRepo.save(binFile);

        // Refresh 5-minute inactivity expiry on the parent paste
        pasteService.touch(pasteId);

        BinFileDto dto = toDto(saved);

        // Broadcast real-time SSE event to all connected PCs viewing this pad
        syncService.broadcastEvent(pasteId, "file_uploaded", dto);

        return dto;
    }

    @Transactional(readOnly = true)
    public List<BinFileDto> getFilesForPaste(String rawPasteId) {
        String pasteId = PasteService.sanitizeId(rawPasteId);
        return fileRepo.findByPasteIdOrderByUploadedAtDesc(pasteId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<BinFile> findById(String fileId) {
        return fileRepo.findById(fileId);
    }

    @Transactional
    public boolean deleteFile(String rawPasteId, String fileId) {
        String pasteId = PasteService.sanitizeId(rawPasteId);
        Optional<BinFile> opt = fileRepo.findById(fileId);
        if (opt.isEmpty()) {
            return false;
        }

        BinFile bf = opt.get();
        if (!bf.getPasteId().equals(pasteId)) {
            return false;
        }

        // Delete from disk
        deletePhysicalFileAndEmptyParents(Paths.get(bf.getStoragePath()));

        fileRepo.delete(bf);

        // Reset paste 5-min timer
        pasteService.touch(pasteId);

        // Broadcast SSE event
        Map<String, String> payload = new HashMap<>();
        payload.put("fileId", fileId);
        payload.put("pasteId", pasteId);
        syncService.broadcastEvent(pasteId, "file_deleted", payload);

        return true;
    }

    /**
     * Purges physical files for expired pastes. Called by CleanupService.
     */
    @Transactional
    public void cleanupExpiredFiles(Instant now) {
        List<BinFile> expiredFiles = fileRepo.findFilesForExpiredPastes(now);
        Set<String> expiredPasteIds = new HashSet<>();

        for (BinFile bf : expiredFiles) {
            expiredPasteIds.add(bf.getPasteId());
            deletePhysicalFileAndEmptyParents(Paths.get(bf.getStoragePath()));
        }

        // Clean up entire folder for expired pastes if empty or any leftovers remain
        for (String pasteId : expiredPasteIds) {
            try {
                Path pasteDir = this.rootStoragePath.resolve(pasteId);
                if (Files.exists(pasteDir)) {
                    org.springframework.util.FileSystemUtils.deleteRecursively(pasteDir);
                }
            } catch (Exception ex) {
                log.warn("Failed deleting paste directory for {}: {}", pasteId, ex.getMessage());
            }
        }

        if (!expiredFiles.isEmpty()) {
            fileRepo.deleteFilesForExpiredPastes(now);
            log.info("Cleanup: deleted {} file(s) and folders from disk for expired pastes", expiredFiles.size());
        }
    }

    private void deletePhysicalFileAndEmptyParents(Path filePath) {
        try {
            // 1. Delete physical file
            Files.deleteIfExists(filePath);

            // 2. Delete file sub-directory (./uploads/{pasteId}/{fileId})
            Path fileDir = filePath.getParent();
            if (fileDir != null && Files.exists(fileDir)) {
                try {
                    Files.deleteIfExists(fileDir);
                } catch (Exception ignored) {}
            }

            // 3. Delete paste directory (./uploads/{pasteId}) if now empty
            if (fileDir != null) {
                Path pasteDir = fileDir.getParent();
                if (pasteDir != null && !pasteDir.equals(this.rootStoragePath) && Files.exists(pasteDir)) {
                    try {
                        Files.deleteIfExists(pasteDir);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete physical file {}: {}", filePath, e.getMessage());
        }
    }

    public static String determineCategory(String filename, String contentType) {
        if (filename == null) return null;
        String lower = filename.toLowerCase();

        if (lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType)) {
            return "PDF";
        }

        if (lower.endsWith(".doc") || lower.endsWith(".docx")
                || "application/msword".equalsIgnoreCase(contentType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)) {
            return "WORD";
        }

        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg")
                || lower.endsWith(".bmp") || (contentType != null && contentType.toLowerCase().startsWith("image/"))) {
            return "IMAGE";
        }

        return null;
    }

    public BinFileDto toDto(BinFile file) {
        String downloadUrl = "/dropbin/" + file.getPasteId() + "/files/" + file.getId() + "/download";
        String viewUrl = "/dropbin/" + file.getPasteId() + "/files/" + file.getId() + "/view";
        return new BinFileDto(
                file.getId(),
                file.getPasteId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getFileSize(),
                file.getFileCategory(),
                file.getUploadedAt(),
                downloadUrl,
                viewUrl
        );
    }
}
