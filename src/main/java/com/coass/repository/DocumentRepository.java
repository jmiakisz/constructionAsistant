package com.coass.repository;

import com.coass.entity.AiIndexingMode;
import com.coass.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    @Query(value = "SELECT * FROM documents WHERE project_id = :projectId AND :role = ANY(visible_for_roles)", nativeQuery = true)
    List<Document> findByProjectIdAndRole(@Param("projectId") Long projectId, @Param("role") String role);

    List<Document> findByProjectId(Long projectId);

    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId AND d.status = 'PROCESSING'")
    List<Document> findProcessingByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT d FROM Document d WHERE d.project.id = :projectId AND d.extractedData IS NOT NULL")
    List<Document> findByProjectIdWithExtractedData(@Param("projectId") Long projectId);

    long countByProjectIdAndAiIndexingModeAndStatus(Long projectId, AiIndexingMode aiIndexingMode, String status);
}
