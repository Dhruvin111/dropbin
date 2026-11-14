package com.textbin.service;

import com.textbin.dto.BinFileDto;
import com.textbin.model.BinFile;
import com.textbin.repository.BinFileRepository;
import com.textbin.repository.PasteRepository;
import com.textbin.util.FileValidationUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10MB
    public static final int MAX_FILES_PER_BIN = 5;

    private static final long MIN_FREE_DISK_BYTES = 50L * 1024 * 1024; // keep at least 50MB free
    private static final int MAX_FILENAME_LENGTH = 100;

    private final BinFileRepository fileRepo;
    private final PasteRepository pasteRepo;
    private final PasteService pasteService;
    private final PasteSyncService syncService;

    @Value("${app.upload.dir:./uploads}")
    private String uploadBaseDir;

    private Path rootStoragePath;

    public FileStorageService(BinFileRepository fileRepo, PasteRepository pasteRepo, PasteService pasteService, PasteSyncService syncService) {
        this.fileRepo = fileRepo;
        this.pasteRepo = pasteRepo;
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

        // ---- 1. Cheap validation first, so invalid uploads never create a paste or touch the disk ----
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload an empty file.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size exceeds the 10MB limit (" + (file.getSize() / (1024 * 1024)) + "MB).");
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());

        String category = FileValidationUtils.determineCategory(originalFilename);
        if (category == null) {
            throw new IllegalArgumentException("Unsupported file type. Only standard Image, PDF, and Word (.doc, .docx) files are supported.");
        }
        String contentType = FileValidationUtils.resolveSafeContentType(originalFilename);

        // Verify magic bytes / structure so the content matches the declared extension
        if (!FileValidationUtils.isValidFileSignature(originalFilename, file)) {
            throw new IllegalArgumentException("File content does not match the expected " + category + " file format. Invalid or corrupted file header.");
        }

        ensureDiskSpace(pasteId, file.getSize());

        // ---- 2. Ensure paste exists, then lock it for the duration of this transaction ----
        pasteService.getOrCreate(pasteId);

        // The pessimistic row lock is held until this transaction commits, which serialises
        // concurrent uploads to the same paste and makes the file-count check below reliable.
        // (A JVM-level synchronized block would release BEFORE commit, so it can't do this.)
        pasteRepo.findByIdForUpdate(pasteId).orElseGet(() -> pasteService.getOrCreate(pasteId));

        long currentCount = fileRepo.countByPasteId(pasteId);
        if (currentCount >= MAX_FILES_PER_BIN) {
            throw new IllegalStateException("Maximum limit of " + MAX_FILES_PER_BIN + " files reached for this workspace. Please delete an existing file before uploading a new one.");
        }

        // ---- 3. Write to disk: ./uploads/{pasteId}/{fileId}/{filename} ----
        String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Path targetDir = this.rootStoragePath.resolve(pasteId).resolve(fileId).normalize();
        if (!targetDir.startsWith(this.rootStoragePath)) {
            throw new SecurityException("Invalid paste directory resolution.");
        }

        Path targetFilePath = targetDir.resolve(originalFilename).normalize();
        // Strict safety check: must be a direct child of targetDir
        if (targetFilePath.getParent() == null || !targetFilePath.getParent().equals(targetDir)) {
            throw new SecurityException("Invalid file path resolution.");
        }

        // If the DB transaction rolls back for any reason, don't leave an orphan file on disk.
        runAfterRollback(() -> deletePhysicalFileAndEmptyParents(targetFilePath));

        Files.createDirectories(targetDir);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetFilePath); // fails if it already exists (it never should)
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

        // Broadcast only after commit, so clients that react to the event and re-fetch the list will see the file
        runAfterCommit(() -> syncService.broadcastEvent(pasteId, "file_uploaded", dto));

        return dto;
    }

    @Transactional(readOnly = true)
    public List<BinFileDto> getFilesForPaste(String rawPasteId) {
        return getFilesForPaste(rawPasteId, null);
    }

    @Transactional(readOnly = true)
    public List<BinFileDto> getFilesForPaste(String rawPasteId, String routePrefix) {
        String pasteId = PasteService.sanitizeId(rawPasteId);
        return fileRepo.findByPasteIdOrderByUploadedAtDesc(pasteId)
                .stream()
                .map(f -> toDto(f, routePrefix))
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

        Path storagePath = Paths.get(bf.getStoragePath());

        fileRepo.delete(bf);

        // Reset paste 5-min timer
        pasteService.touch(pasteId);

        // Remove from disk and notify only once the DB delete has committed
        // (worst case on failure is an orphan file, never a DB row pointing at a missing file).
        Map<String, String> payload = new HashMap<>();
        payload.put("fileId", fileId);
        payload.put("pasteId", pasteId);
        runAfterCommit(() -> {
            deletePhysicalFileAndEmptyParents(storagePath);
            syncService.broadcastEvent(pasteId, "file_deleted", payload);
        });

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

        // Clean up entire folder for expired pastes if any leftovers remain
        for (String pasteId : expiredPasteIds) {
            deletePasteDirectory(pasteId);
        }

        if (!expiredFiles.isEmpty()) {
            fileRepo.deleteFilesForExpiredPastes(now);
            log.info("Cleanup: deleted {} file(s) and folders from disk for expired pastes", expiredFiles.size());
        }
    }

    /**
     * Purges all physical files and database records for a specific paste when taken down.
     */
    @Transactional
    public void deleteAllFilesForPaste(String rawPasteId) {
        String pasteId = PasteService.sanitizeId(rawPasteId);
        try {
            List<BinFile> files = fileRepo.findByPasteIdOrderByUploadedAtDesc(pasteId);
            for (BinFile bf : files) {
                try {
                    deletePhysicalFileAndEmptyParents(Paths.get(bf.getStoragePath()));
                } catch (Exception e) {
                    log.warn("Could not delete physical file {}: {}", bf.getStoragePath(), e.getMessage());
                }
            }
            deletePasteDirectory(pasteId);
        } catch (Exception ex) {
            log.warn("Error during file cleanup for paste {}: {}", pasteId, ex.getMessage());
        }

        try {
            int deletedCount = fileRepo.deleteByPasteId(pasteId);
            log.info("Deleted {} file record(s) for paste {}", deletedCount, pasteId);
        } catch (Exception ex) {
            log.warn("Query delete failed, falling back to deleteAll: {}", ex.getMessage());
            try {
                List<BinFile> remaining = fileRepo.findByPasteIdOrderByUploadedAtDesc(pasteId);
                if (!remaining.isEmpty()) {
                    fileRepo.deleteAll(remaining);
                }
            } catch (Exception ex2) {
                log.error("Error deleting DB records for paste files {}: {}", pasteId, ex2.getMessage());
            }
        }

        // Broadcast SSE event so all collaborators' file cards clear immediately
        Map<String, String> payload = new HashMap<>();
        payload.put("pasteId", pasteId);
        runAfterCommit(() -> {
            try {
                syncService.broadcastEvent(pasteId, "files_cleared", payload);
            } catch (Exception ex) {
                log.warn("Failed to broadcast files_cleared event for {}: {}", pasteId, ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Reduces a client-supplied filename to a safe, bounded name:
     * strips any path, keeps only [a-zA-Z0-9._-], removes leading dots, caps the length.
     */
    private static String sanitizeFilename(String raw) {
        String fallbackPrefix = "upload_" + System.currentTimeMillis();
        if (raw == null || raw.isBlank()) {
            return fallbackPrefix;
        }

        // Strip any directory part (both separators) without going through Paths.get(),
        // which throws InvalidPathException on e.g. null bytes.
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        // Disallow Windows ADS (:), control characters, spaces, etc.
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");

        if (name.startsWith(".") || name.isBlank()) {
            name = fallbackPrefix + "_" + name.replaceFirst("^\\.+", "");
        }

        if (name.length() > MAX_FILENAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 10) {
                String ext = name.substring(dot);
                name = name.substring(0, MAX_FILENAME_LENGTH - ext.length()) + ext;
            } else {
                name = name.substring(0, MAX_FILENAME_LENGTH);
            }
        }
        return name;
    }

    private void ensureDiskSpace(String pasteId, long incomingBytes) {
        try {
            long usableSpace = Files.getFileStore(this.rootStoragePath).getUsableSpace();
            if (usableSpace < MIN_FREE_DISK_BYTES + incomingBytes) {
                log.error("Rejecting upload for paste {}: usable disk space too low ({} MB)",
                        pasteId, usableSpace / (1024 * 1024));
                throw new IllegalStateException("Server disk storage is almost full. Uploads are temporarily paused.");
            }
        } catch (IOException e) {
            log.warn("Could not check disk usable space: {}", e.getMessage());
        }
    }

    private void deletePasteDirectory(String pasteId) {
        try {
            Path pasteDir = this.rootStoragePath.resolve(pasteId).normalize();
            if (pasteDir.startsWith(this.rootStoragePath) && !pasteDir.equals(this.rootStoragePath) && Files.exists(pasteDir)) {
                FileSystemUtils.deleteRecursively(pasteDir);
            }
        } catch (Exception ex) {
            log.warn("Failed deleting paste directory for {}: {}", pasteId, ex.getMessage());
        }
    }

    private void deletePhysicalFileAndEmptyParents(Path filePath) {
        try {
            // Never delete anything outside the upload root, even if a DB row is tampered with
            Path normalized = filePath.toAbsolutePath().normalize();
            if (!normalized.startsWith(this.rootStoragePath) || normalized.equals(this.rootStoragePath)) {
                log.warn("Refusing to delete path outside upload root: {}", filePath);
                return;
            }

            // 1. Delete physical file
            Files.deleteIfExists(normalized);

            // 2. Delete file sub-directory (./uploads/{pasteId}/{fileId}) if empty
            Path fileDir = normalized.getParent();
            if (fileDir != null && !fileDir.equals(this.rootStoragePath) && Files.exists(fileDir)) {
                try {
                    Files.deleteIfExists(fileDir);
                } catch (Exception ignored) {
                    // directory not empty or already gone
                }
            }

            // 3. Delete paste directory (./uploads/{pasteId}) if now empty
            if (fileDir != null) {
                Path pasteDir = fileDir.getParent();
                if (pasteDir != null && pasteDir.startsWith(this.rootStoragePath)
                        && !pasteDir.equals(this.rootStoragePath) && Files.exists(pasteDir)) {
                    try {
                        Files.deleteIfExists(pasteDir);
                    } catch (Exception ignored) {
                        // directory not empty or already gone
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete physical file {}: {}", filePath, e.getMessage());
        }
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static void runAfterRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        action.run();
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Backwards-compatible static wrappers (logic now lives in FileValidationUtils).
    // Update callers to use FileValidationUtils directly, then delete these.
    // ------------------------------------------------------------------

    /** @deprecated the header argument is ignored; use {@link FileValidationUtils#isValidFileSignature(String, MultipartFile)}. */
    @Deprecated
    public static boolean isValidFileSignature(String filename, byte[] header, MultipartFile file) {
        return FileValidationUtils.isValidFileSignature(filename, file);
    }

    /** @deprecated use {@link FileValidationUtils#determineCategory(String)}. */
    @Deprecated
    public static String determineCategory(String filename) {
        return FileValidationUtils.determineCategory(filename);
    }

    /** @deprecated use {@link FileValidationUtils#determineCategory(String)}. */
    @Deprecated
    public static String determineCategory(String filename, String contentType) {
        return FileValidationUtils.determineCategory(filename);
    }

    /** @deprecated use {@link FileValidationUtils#resolveSafeContentType(String)}. */
    @Deprecated
    public static String resolveSafeContentType(String filename) {
        return FileValidationUtils.resolveSafeContentType(filename);
    }

    public BinFileDto toDto(BinFile file) {
        return toDto(file, null);
    }

    public BinFileDto toDto(BinFile file, String routePrefix) {
        String base = (routePrefix != null && !routePrefix.isBlank()) ? routePrefix : file.getPasteId();
        String downloadUrl = "/dropbin/" + base + "/files/" + file.getId() + "/download";
        String viewUrl = "/dropbin/" + base + "/files/" + file.getId() + "/view";
        return new BinFileDto(
                file.getId(),
                base,
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