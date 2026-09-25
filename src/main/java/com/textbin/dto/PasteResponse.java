package com.textbin.dto;

import java.time.Instant;

public class PasteResponse {
    private String id;
    private String content;
    private String title;
    private String syntaxLanguage;
    private Long version;
    private Instant updatedAt;
    private String clientToken;

    public PasteResponse() {}

    public PasteResponse(String id, String content, String title, String syntaxLanguage,
                         Long version, Instant updatedAt, String clientToken) {
        this.id = id;
        this.content = content;
        this.title = title;
        this.syntaxLanguage = syntaxLanguage;
        this.version = version;
        this.updatedAt = updatedAt;
        this.clientToken = clientToken;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSyntaxLanguage() {
        return syntaxLanguage;
    }

    public void setSyntaxLanguage(String syntaxLanguage) {
        this.syntaxLanguage = syntaxLanguage;
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

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
