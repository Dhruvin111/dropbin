package com.textbin.repository;

import com.textbin.model.BinFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface BinFileRepository extends JpaRepository<BinFile, String> {

    List<BinFile> findByPasteIdOrderByUploadedAtDesc(String pasteId);

    @Modifying
    @Transactional
    @Query("DELETE FROM BinFile b WHERE b.pasteId = :pasteId")
    int deleteByPasteId(String pasteId);

    @Query("SELECT b FROM BinFile b WHERE b.pasteId IN (SELECT p.id FROM Paste p WHERE p.expiresAt < :now)")
    List<BinFile> findFilesForExpiredPastes(Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM BinFile b WHERE b.pasteId IN (SELECT p.id FROM Paste p WHERE p.expiresAt < :now)")
    int deleteFilesForExpiredPastes(Instant now);
}
