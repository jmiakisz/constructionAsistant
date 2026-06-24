package com.coass.repository;

import com.coass.entity.ProjectAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectAlertRepository extends JpaRepository<ProjectAlert, Long> {
    List<ProjectAlert> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<ProjectAlert> findByProjectIdAndDocumentIdOrderByCreatedAtDesc(Long projectId, Long documentId);

    void deleteByProjectId(Long projectId);
}
