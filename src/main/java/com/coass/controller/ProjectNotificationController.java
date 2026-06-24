package com.coass.controller;

import com.coass.security.CoassUserDetails;
import com.coass.service.ProjectNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/notifications")
@RequiredArgsConstructor
public class ProjectNotificationController {

    private final ProjectNotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(notificationService.listForProject(projectId, user.getUserId()));
    }

    @GetMapping("/pending-count")
    public ResponseEntity<Map<String, Object>> pendingCount(@PathVariable Long projectId) {
        return ResponseEntity.ok(Map.of("count", notificationService.countPending(projectId)));
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<Map<String, Object>> reply(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(notificationService.reply(id, user.getUserId(), body.get("response")));
    }
}
