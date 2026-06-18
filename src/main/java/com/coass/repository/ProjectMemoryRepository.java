package com.coass.repository;

import com.coass.entity.ProjectMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMemoryRepository extends JpaRepository<ProjectMemory, Long> {

    Optional<ProjectMemory> findByProjectIdAndRole(Long projectId, String role);

    List<ProjectMemory> findByProjectId(Long projectId);
}
