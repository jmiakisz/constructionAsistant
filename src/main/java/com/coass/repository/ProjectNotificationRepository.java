package com.coass.repository;

import com.coass.entity.ProjectNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectNotificationRepository extends JpaRepository<ProjectNotification, Long> {

    @Query("SELECT n FROM ProjectNotification n WHERE n.project.id = :projectId ORDER BY n.createdAt DESC")
    List<ProjectNotification> findAllForProject(@Param("projectId") Long projectId);

    @Query("SELECT n FROM ProjectNotification n WHERE n.project.id = :projectId AND n.senderUser.id = :userId ORDER BY n.createdAt DESC")
    List<ProjectNotification> findForUser(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Query("SELECT COUNT(n) FROM ProjectNotification n WHERE n.project.id = :projectId AND n.status = 'PENDING'")
    long countPendingForProject(@Param("projectId") Long projectId);
}
