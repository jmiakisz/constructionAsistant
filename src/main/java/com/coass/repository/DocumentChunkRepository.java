package com.coass.repository;

import com.coass.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    // Similarity search — zwraca top K chunków widocznych dla danej roli
    @Query(value = """
        SELECT dc.* FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.project_id = :projectId
          AND :role = ANY(d.visible_for_roles)
          AND dc.embedding IS NOT NULL
        ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<DocumentChunk> findSimilar(
        @Param("projectId") Long projectId,
        @Param("role") String role,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("topK") int topK
    );

    List<DocumentChunk> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
