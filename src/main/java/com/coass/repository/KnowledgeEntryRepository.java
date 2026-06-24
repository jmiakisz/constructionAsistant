package com.coass.repository;

import com.coass.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    // Firmowa (project_id=NULL) i projektowa (project_id=projectId) — obie filtrowane przez source_role
    // Wiedza firmowa zawiera dane wrażliwe (obroty, zasoby) — dostępna tylko dla odpowiednich ról
    @Query(value = """
        SELECT ke.* FROM knowledge_entries ke
        WHERE ke.embedding IS NOT NULL
          AND ke.source_role IN :visibleRoles
          AND (ke.project_id IS NULL OR ke.project_id = :projectId)
          AND (ke.embedding <=> CAST(:queryEmbedding AS vector)) <= :maxDistance
        ORDER BY ke.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<KnowledgeEntry> findSimilarForProject(
        @Param("projectId") Long projectId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("visibleRoles") List<String> visibleRoles,
        @Param("topK") int topK,
        @Param("maxDistance") double maxDistance
    );

    @Query(value = """
        SELECT ke.* FROM knowledge_entries ke
        WHERE ke.embedding IS NOT NULL
          AND ke.source_role IN :visibleRoles
        ORDER BY ke.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<KnowledgeEntry> findSimilar(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("visibleRoles") List<String> visibleRoles,
        @Param("topK") int topK
    );

    @Modifying
    @Query(value = """
        INSERT INTO knowledge_entries (project_id, content, embedding, source_role, category, entry_type, valid_until, created_at, last_confirmed_at, confidence)
        VALUES (:projectId, :content, CAST(:embedding AS vector), :sourceRole, :category, :entryType,
                CAST(:validUntil AS timestamp), NOW(), NOW(), 1)
        """, nativeQuery = true)
    void insertWithEmbedding(
        @Param("projectId") Long projectId,
        @Param("content") String content,
        @Param("embedding") String embedding,
        @Param("sourceRole") String sourceRole,
        @Param("category") String category,
        @Param("entryType") String entryType,
        @Param("validUntil") String validUntil
    );

    @Modifying
    @Query("DELETE FROM KnowledgeEntry ke WHERE ke.entryType = 'TEMPORAL' AND ke.validUntil IS NOT NULL AND ke.validUntil < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE KnowledgeEntry ke SET ke.confidence = ke.confidence - 1 WHERE ke.lastConfirmedAt < :cutoff AND ke.confidence > 0")
    int degradeOldEntries(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM KnowledgeEntry ke WHERE ke.confidence <= 0")
    int deleteZeroConfidence();

    @Query(value = """
        SELECT a.id, b.id
        FROM knowledge_entries a
        JOIN knowledge_entries b ON a.id < b.id
        WHERE a.embedding IS NOT NULL AND b.embedding IS NOT NULL
          AND (a.embedding <=> b.embedding) < :maxDistance
        LIMIT 100
        """, nativeQuery = true)
    List<Object[]> findPotentialDuplicates(@Param("maxDistance") double maxDistance);

    List<KnowledgeEntry> findByProjectId(Long projectId);

    @Query("SELECT ke FROM KnowledgeEntry ke WHERE ke.project.id = :projectId AND ke.sourceRole IN :visibleRoles ORDER BY ke.createdAt DESC LIMIT 20")
    List<KnowledgeEntry> findRecentForBriefing(@Param("projectId") Long projectId, @Param("visibleRoles") List<String> visibleRoles);
}
