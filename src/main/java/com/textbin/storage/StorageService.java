package com.textbin.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Common abstraction for file persistence.
 * Implementations include LocalStorageService (local disk) and S3StorageService (Cloudflare R2 / AWS S3).
 */
public interface StorageService {

    /**
     * Stores an incoming file stream and returns its storage reference/path.
     */
    String store(String pasteId, String fileId, String filename, InputStream inputStream, long contentLength, String contentType) throws IOException;

    /**
     * Loads a stored file as a Spring Resource for inline viewing or downloading.
     */
    Resource loadAsResource(String storagePath) throws IOException;

    /**
     * Deletes a single physical file.
     */
    void delete(String storagePath);

    /**
     * Deletes all files and folder associated with a pasteId.
     */
    void deleteFolder(String pasteId);

    /**
     * Name of the active storage backend (e.g. "LOCAL", "S3").
     */
    String getStorageType();
}
