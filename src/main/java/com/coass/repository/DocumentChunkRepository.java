package com.coass.repository;

import com.coass.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    // Similarity search — zwraca top K chunków widocznych dla danej roli, tylko powyżej progu trafności
    @Query(value = """
        SELECT dc.* FROM document_chunks dc
        JOIN documents d ON dc.document_id = d.id
        WHERE d.project_id = :projectId
          AND :role = ANY(d.visible_for_roles)
          AND dc.embedding IS NOT NULL
          AND (dc.embedding <=> CAST(:queryEmbedding AS vector)) <= :maxDistance
        ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<DocumentChunk> findSimilar(
        @Param("projectId") Long projectId,
        @Param("role") String role,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("topK") int topK,
        @Param("maxDistance") double maxDistance
    );

    @jakarta.transaction.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        INSERT INTO document_chunks (document_id, content, embedding, chunk_index)
        VALUES (:documentId, :content, CAST(:embedding AS vector), :chunkIndex)
        """, nativeQuery = true)
    void insertWithEmbedding(
        @Param("documentId") Long documentId,
        @Param("content") String content,
        @Param("embedding") String embedding,
        @Param("chunkIndex") int chunkIndex
    );

    List<DocumentChunk> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);

    // Statystyki procesowania dokumentów per projekt
    @Query(value = """
        SELECT p.id, p.name,
               COUNT(DISTINCT d.id)  AS documents,
               COUNT(dc.id)          AS chunks,
               COUNT(dc.id) FILTER (WHERE dc.embedding IS NOT NULL) AS indexed_chunks
        FROM projects p
        LEFT JOIN documents d ON d.project_id = p.id
        LEFT JOIN document_chunks dc ON dc.document_id = d.id
        GROUP BY p.id, p.name
        ORDER BY documents DESC
        """, nativeQuery = true)
    List<Object[]> getDocumentStatsByProject();

    // Procesowanie według dni (ostatnie 30 dni)
    @Query(value = """
        SELECT DATE(d.created_at) AS day,
               COUNT(DISTINCT d.id) AS documents,
               COUNT(dc.id)         AS chunks
        FROM documents d
        LEFT JOIN document_chunks dc ON dc.document_id = d.id
        WHERE d.created_at >= NOW() - INTERVAL '30 days'
        GROUP BY DATE(d.created_at)
        ORDER BY day
        """, nativeQuery = true)
    List<Object[]> getDailyDocumentStats();
}
