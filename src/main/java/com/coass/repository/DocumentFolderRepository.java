package com.coass.repository;

import com.coass.entity.DocumentFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentFolderRepository extends JpaRepository<DocumentFolder, Long> {

    List<DocumentFolder> findByProjectId(Long projectId);

    long countByParentId(Long parentId);
}
