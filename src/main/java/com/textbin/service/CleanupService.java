package com.textbin.service;

import com.textbin.repository.PasteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Runs every 60 seconds and purges all rows and files for pastes
 * whose expires_at timestamp is in the past (5 minutes after last edit/upload).
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final PasteRepository pasteRepo;
    private final FileStorageService fileStorageService;

    public CleanupService(PasteRepository pasteRepo, FileStorageService fileStorageService) {
        this.pasteRepo = pasteRepo;
        this.fileStorageService = fileStorageService;
    }

    @Scheduled(fixedDelay = 60_000)   // every 60 seconds after last run finishes
    @Transactional
    public void purgeExpiredPastes() {
        Instant now = Instant.now();

        // 1. Delete physical files and records from bin_files
        try {
            fileStorageService.cleanupExpiredFiles(now);
        } catch (Exception ex) {
            log.error("Error during expired file cleanup: {}", ex.getMessage());
        }

        // 2. Delete expired pastes from database
        int deleted = pasteRepo.deleteAllExpiredBefore(now);
        if (deleted > 0) {
            log.info("Cleanup: deleted {} expired paste(s)", deleted);
        }
    }
}
