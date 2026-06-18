package com.coass.repository;

import com.coass.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    // Similarity search — wiedza firmowa widoczna dla danej roli (source_role <= user_role)
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
}
