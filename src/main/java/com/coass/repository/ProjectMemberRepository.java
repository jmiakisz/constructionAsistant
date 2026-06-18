package com.coass.repository;

import com.coass.entity.ProjectMember;
import com.coass.entity.ProjectMemberId;
import com.coass.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    @Query("SELECT m.role FROM ProjectMember m WHERE m.project.id = :projectId AND m.user.id = :userId")
    Optional<Role> findRoleByProjectAndUser(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
