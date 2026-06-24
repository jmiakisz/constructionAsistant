package com.coass.repository;

import com.coass.entity.ProjectBriefing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectBriefingRepository extends JpaRepository<ProjectBriefing, Long> {
    Optional<ProjectBriefing> findByProjectIdAndRoleName(Long projectId, String roleName);
}
