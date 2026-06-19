package com.coass.controller;

import com.coass.dto.project.ProjectRequest;
import com.coass.dto.project.ProjectResponse;
import com.coass.entity.ProjectAlert;
import com.coass.entity.Role;
import com.coass.repository.ProjectAlertRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAlertRepository alertRepository;

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody ProjectRequest req,
            @AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(projectService.create(req, user.getUserId()));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(projectService.listForUser(user.getUserId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(
            @PathVariable Long id,
            @AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(projectService.getForUser(id, user.getUserId()));
    }

    @GetMapping("/{id}/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts(
            @PathVariable Long id,
            @AuthenticationPrincipal CoassUserDetails user) {
        List<Map<String, Object>> result = alertRepository
                .findByProjectIdOrderByCreatedAtDesc(id)
                .stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.getId(),
                        "level", a.getLevel(),
                        "message", a.getMessage(),
                        "createdAt", a.getCreatedAt(),
                        "documentId", a.getDocument() != null ? a.getDocument().getId() : null
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<Void> addMember(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestParam Role role,
            @AuthenticationPrincipal CoassUserDetails user) {
        projectService.addMember(id, user.getUserId(), userId, role);
        return ResponseEntity.ok().build();
    }
}
