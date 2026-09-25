package com.textbin.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "pastes", indexes = {
    @Index(name = "idx_pastes_expires_at", columnList = "expires_at")
})
public class Paste {

    @Id
    @Column(name = "id", length = 255)
    private String id;

    @Size(max = 2_000_000, message = "Content too large (max 2MB)")
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content = "";

    @Size(max = 255)
    @Column(name = "title")
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "version")
    private Long version = 1L;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "syntax_language", length = 50)
    private String syntaxLanguage = "plaintext";

    // ─── Constructors ────────────────────────────────────────────────────────

    public Paste() {}

    public Paste(String id, String content, String title, Instant createdAt,
                 Instant expiresAt, String syntaxLanguage) {
        this.id = id;
        this.content = content != null ? content : "";
        this.title = title;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = this.createdAt;
        this.expiresAt = expiresAt != null ? expiresAt : this.createdAt.plus(5, ChronoUnit.MINUTES);
        this.syntaxLanguage = (syntaxLanguage != null && !syntaxLanguage.isBlank()) ? syntaxLanguage : "plaintext";
        this.version = 1L;
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (content == null) content = "";
        if (version == null) version = 1L;
        if (expiresAt == null) expiresAt = createdAt.plus(5, ChronoUnit.MINUTES);
        if (syntaxLanguage == null || syntaxLanguage.isBlank()) syntaxLanguage = "plaintext";
    }

    @PreUpdate
    public void preUpdate() {
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
        if (this.version == null) {
            this.version = 1L;
        }
        // Actively updated textbins stay alive for 5 minutes after last edit
        this.expiresAt = this.updatedAt.plus(5, ChronoUnit.MINUTES);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(this.expiresAt);
    }

    public long secondsUntilExpiry() {
        if (expiresAt == null) return Long.MAX_VALUE;
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    // ─── Getters / Setters ───────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content != null ? content : ""; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version != null ? version : 1L; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getSyntaxLanguage() { return syntaxLanguage; }
    public void setSyntaxLanguage(String syntaxLanguage) { this.syntaxLanguage = syntaxLanguage; }
}
