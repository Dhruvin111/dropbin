package com.textbin.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Storage implementation writing to the local container/machine filesystem.
 */
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);
    private static final long MIN_FREE_DISK_BYTES = 50L * 1024 * 1024; // 50MB reserve

    private final Path rootStoragePath;

    public LocalStorageService(String uploadBaseDir) {
        this.rootStoragePath = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootStoragePath);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize local upload directory: " + this.rootStoragePath, e);
        }
        log.info("Initialized LocalStorageService at: {}", this.rootStoragePath);
    }

    @Override
    public String store(String pasteId, String fileId, String filename, InputStream inputStream, long contentLength, String contentType) throws IOException {
        ensureDiskSpace(contentLength);

        Path targetDir = this.rootStoragePath.resolve(pasteId).resolve(fileId).normalize();
        if (!targetDir.startsWith(this.rootStoragePath)) {
            throw new SecurityException("Invalid paste directory resolution.");
        }

        Path targetFilePath = targetDir.resolve(filename).normalize();
        if (targetFilePath.getParent() == null || !targetFilePath.getParent().equals(targetDir)) {
            throw new SecurityException("Invalid file path resolution.");
        }

        Files.createDirectories(targetDir);
        Files.copy(inputStream, targetFilePath);

        return targetFilePath.toString();
    }

    @Override
    public Resource loadAsResource(String storagePath) throws IOException {
        Path path = Paths.get(storagePath).toAbsolutePath().normalize();
        if (!path.startsWith(this.rootStoragePath)) {
            log.warn("Attempt to access file outside upload directory: {}", storagePath);
            return null;
        }

        Resource resource = new UrlResource(path.toUri());
        if (resource.exists() && resource.isReadable()) {
            return resource;
        }
        return null;
    }

    @Override
    public void delete(String storagePath) {
        try {
            Path filePath = Paths.get(storagePath).toAbsolutePath().normalize();
            if (!filePath.startsWith(this.rootStoragePath) || filePath.equals(this.rootStoragePath)) {
                log.warn("Refusing to delete path outside upload root: {}", storagePath);
                return;
            }

            Files.deleteIfExists(filePath);

            // Clean up file subdirectory if empty
            Path fileDir = filePath.getParent();
            if (fileDir != null && !fileDir.equals(this.rootStoragePath) && Files.exists(fileDir)) {
                try { Files.deleteIfExists(fileDir); } catch (Exception ignored) {}
            }

            // Clean up paste directory if empty
            if (fileDir != null) {
                Path pasteDir = fileDir.getParent();
                if (pasteDir != null && pasteDir.startsWith(this.rootStoragePath) && !pasteDir.equals(this.rootStoragePath) && Files.exists(pasteDir)) {
                    try { Files.deleteIfExists(pasteDir); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete physical file {}: {}", storagePath, e.getMessage());
        }
    }

    @Override
    public void deleteFolder(String pasteId) {
        try {
            Path pasteDir = this.rootStoragePath.resolve(pasteId).normalize();
            if (pasteDir.startsWith(this.rootStoragePath) && !pasteDir.equals(this.rootStoragePath) && Files.exists(pasteDir)) {
                FileSystemUtils.deleteRecursively(pasteDir);
            }
        } catch (Exception ex) {
            log.warn("Failed deleting paste directory for {}: {}", pasteId, ex.getMessage());
        }
    }

    @Override
    public String getStorageType() {
        return "LOCAL";
    }

    private void ensureDiskSpace(long incomingBytes) {
        try {
            long usableSpace = Files.getFileStore(this.rootStoragePath).getUsableSpace();
            if (usableSpace < MIN_FREE_DISK_BYTES + incomingBytes) {
                throw new IllegalStateException("Server disk storage is almost full. Uploads are temporarily paused.");
            }
        } catch (IOException e) {
            log.warn("Could not check disk usable space: {}", e.getMessage());
        }
    }
}
