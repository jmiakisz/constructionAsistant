package com.coass.dto.project;

import com.coass.entity.Project;
import com.coass.entity.Role;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt,
        Role userRole
) {
    public static ProjectResponse from(Project p, Role role) {
        return new ProjectResponse(p.getId(), p.getName(), p.getDescription(), p.getCreatedAt(), role);
    }
}
