package com.coass.controller;

import com.coass.security.CoassUserDetails;
import com.coass.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info("HTTP POST /chat projectId={} userId={} message='{}'", projectId, user.getUserId(), message);
        String response = chatService.chat(projectId, user.getUserId(), message);
        log.info("HTTP POST /chat projectId={} -> responseLength={}", projectId, response.length());
        return ResponseEntity.ok(Map.of("response", response));
    }
}
