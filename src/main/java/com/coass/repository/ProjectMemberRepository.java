package com.coass.repository;

import com.coass.entity.ProjectMember;
import com.coass.entity.ProjectMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    @Query("SELECT m.roleKey FROM ProjectMember m WHERE m.project.id = :projectId AND m.user.id = :userId")
    Optional<String> findRoleByProjectAndUser(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
