package com.coass.repository;

import com.coass.entity.DocumentTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentTypeConfigRepository extends JpaRepository<DocumentTypeConfig, String> {
    List<DocumentTypeConfig> findAllByOrderBySortOrderAsc();
}
