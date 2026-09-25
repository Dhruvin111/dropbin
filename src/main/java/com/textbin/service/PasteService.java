package com.textbin.service;

import com.textbin.dto.PasteResponse;
import com.textbin.model.Paste;
import com.textbin.repository.PasteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasteService {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RANDOM_ID_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasteRepository repo;
    private final PasteSyncService syncService;

    public PasteService(PasteRepository repo, PasteSyncService syncService) {
        this.repo = repo;
        this.syncService = syncService;
    }

    /**
     * Finds existing paste by ID or creates a new one in the database if it doesn't exist.
     */
    @Transactional
    public Paste getOrCreate(String rawId) {
        String cleanId = sanitizeId(rawId);
        Paste paste = repo.findById(cleanId)
                .filter(p -> !p.isExpired())
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    Paste newPaste = new Paste();
                    newPaste.setId(cleanId);
                    newPaste.setContent("");
                    newPaste.setTitle(cleanId);
                    newPaste.setCreatedAt(now);
                    newPaste.setUpdatedAt(now);
                    newPaste.setVersion(1L);
                    newPaste.setExpiresAt(now.plus(5, ChronoUnit.MINUTES));
                    newPaste.setSyntaxLanguage("plaintext");
                    newPaste.setReadOnlyKey(generateReadOnlyKey());
                    return repo.save(newPaste);
                });

        if (paste.getReadOnlyKey() == null || paste.getReadOnlyKey().isBlank()) {
            paste.setReadOnlyKey(generateReadOnlyKey());
            paste = repo.save(paste);
        }
        return paste;
    }

    @Transactional
    public Optional<Paste> findByReadOnlyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        String cleanKey = rawKey.trim();
        Optional<Paste> opt = repo.findByReadOnlyKey(cleanKey).filter(p -> !p.isExpired());
        if (opt.isPresent()) {
            Paste p = opt.get();
            if (p.getReadOnlyKey() == null || p.getReadOnlyKey().isBlank()) {
                p.setReadOnlyKey(generateReadOnlyKey());
                repo.save(p);
            }
        }
        return opt;
    }

    public boolean isReadOnlyKey(String id) {
        if (id == null || id.isBlank()) return false;
        return repo.existsByReadOnlyKey(id.trim());
    }

    public String generateReadOnlyKey() {
        String key;
        do {
            key = "ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        } while (repo.existsByReadOnlyKey(key));
        return key;
    }

    @Transactional(readOnly = true)
    public Optional<Paste> findById(String rawId) {
        String cleanId = sanitizeId(rawId);
        return repo.findById(cleanId).filter(p -> !p.isExpired());
    }

    /**
     * Saves / updates content, increments version, extends expiry, and broadcasts to other connected PCs/clients.
     */
    @Transactional
    public Paste saveOrUpdate(String rawId, String content, String title, String syntaxLanguage, String clientToken) {
        String cleanId = sanitizeId(rawId);
        Instant now = Instant.now();

        Paste paste = repo.findById(cleanId).orElseGet(() -> {
            Paste p = new Paste();
            p.setId(cleanId);
            p.setCreatedAt(now);
            p.setVersion(0L);
            return p;
        });

        paste.setContent(content != null ? content : "");
        if (title != null && !title.isBlank()) {
            paste.setTitle(title.trim());
        }
        if (syntaxLanguage != null && !syntaxLanguage.isBlank()) {
            paste.setSyntaxLanguage(syntaxLanguage.trim());
        }

        paste.setUpdatedAt(now);
        Long currentVer = paste.getVersion() != null ? paste.getVersion() : 0L;
        paste.setVersion(currentVer + 1L);
        // Expiration is 5 minutes after last edit
        paste.setExpiresAt(now.plus(5, ChronoUnit.MINUTES));

        Paste saved = repo.save(paste);

        // Broadcast real-time update to all other connected tabs / PCs
        PasteResponse update = new PasteResponse(
                saved.getId(),
                saved.getContent(),
                saved.getTitle(),
                saved.getSyntaxLanguage(),
                saved.getVersion(),
                saved.getUpdatedAt(),
                clientToken
        );
        syncService.broadcastUpdate(cleanId, update);

        return saved;
    }

    @Transactional
    public void touch(String rawId) {
        String cleanId = sanitizeId(rawId);
        repo.findById(cleanId).ifPresent(p -> {
            Instant now = Instant.now();
            p.setUpdatedAt(now);
            p.setExpiresAt(now.plus(5, ChronoUnit.MINUTES));
            repo.save(p);
        });
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return repo.countActivePastes(Instant.now());
    }

    public String generateUniqueRandomId() {
        String id;
        do {
            StringBuilder sb = new StringBuilder(RANDOM_ID_LENGTH);
            for (int i = 0; i < RANDOM_ID_LENGTH; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            id = sb.toString();
        } while (repo.existsById(id));
        return id;
    }

    public static String sanitizeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "pad";
        }
        String clean = rawId.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        clean = clean.replace(" ", "-");
        return clean.isBlank() ? "pad" : clean;
    }
}
