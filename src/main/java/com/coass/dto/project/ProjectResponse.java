package com.coass.dto.project;

import com.coass.entity.Project;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt,
        String userRole
) {
    public static ProjectResponse from(Project p, String roleKey) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getCreatedAt(), roleKey);
    }
}
