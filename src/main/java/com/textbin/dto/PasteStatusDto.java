package com.textbin.dto;

import java.time.Instant;

public class PasteStatusDto {
    private String id;
    private Long version;
    private Instant updatedAt;
    private int contentLength;

    public PasteStatusDto() {}

    public PasteStatusDto(String id, Long version, Instant updatedAt, int contentLength) {
        this.id = id;
        this.version = version;
        this.updatedAt = updatedAt;
        this.contentLength = contentLength;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }
}
