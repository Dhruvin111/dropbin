package com.textbin.dto;

import java.time.Instant;

public class BinFileDto {
    private String id;
    private String pasteId;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String fileCategory; // "IMAGE", "PDF", "WORD"
    private Instant uploadedAt;
    private String downloadUrl;
    private String viewUrl;

    public BinFileDto() {}

    public BinFileDto(String id, String pasteId, String originalFilename, String contentType,
                      Long fileSize, String fileCategory, Instant uploadedAt,
                      String downloadUrl, String viewUrl) {
        this.id = id;
        this.pasteId = pasteId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileCategory = fileCategory;
        this.uploadedAt = uploadedAt;
        this.downloadUrl = downloadUrl;
        this.viewUrl = viewUrl;
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

    public String getFileCategory() { return fileCategory; }
    public void setFileCategory(String fileCategory) { this.fileCategory = fileCategory; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getViewUrl() { return viewUrl; }
    public void setViewUrl(String viewUrl) { this.viewUrl = viewUrl; }
}
