package com.coass.controller;

import com.coass.dto.project.ProjectRequest;
import com.coass.dto.project.ProjectResponse;
import com.coass.entity.Role;
import com.coass.security.CoassUserDetails;
import com.coass.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

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
