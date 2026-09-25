package com.textbin.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bin_files", indexes = {
    @Index(name = "idx_bin_files_paste_id", columnList = "paste_id")
})
public class BinFile {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "paste_id", length = 255, nullable = false)
    private String pasteId;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "content_type", length = 128, nullable = false)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "storage_path", length = 512, nullable = false)
    private String storagePath;

    @Column(name = "file_category", length = 32, nullable = false)
    private String fileCategory; // "IMAGE", "PDF", "WORD", "OTHER"

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public BinFile() {}

    public BinFile(String id, String pasteId, String originalFilename, String contentType,
                   Long fileSize, String storagePath, String fileCategory, Instant uploadedAt) {
        this.id = id;
        this.pasteId = pasteId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.storagePath = storagePath;
        this.fileCategory = fileCategory;
        this.uploadedAt = uploadedAt != null ? uploadedAt : Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (uploadedAt == null) uploadedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPasteId() { return pasteId; }
    public void setPasteId(String pasteId) { this.pasteId = pasteId; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
