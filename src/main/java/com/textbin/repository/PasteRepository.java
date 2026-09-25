package com.textbin.repository;

import com.textbin.model.Paste;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface PasteRepository extends JpaRepository<Paste, String> {

    /**
     * Bulk-delete all pastes whose expiry timestamp is in the past.
     * Called every minute by CleanupService.
     */
    @Modifying
    @Query("DELETE FROM Paste p WHERE p.expiresAt < :now")
    int deleteAllExpiredBefore(Instant now);

    /** Count how many pastes are still alive (for optional stats endpoint). */
    @Query("SELECT COUNT(p) FROM Paste p WHERE p.expiresAt >= :now")
    long countActivePastes(Instant now);
}
