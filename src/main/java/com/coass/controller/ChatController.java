package com.coass.controller;

import com.coass.entity.Conversation;
import com.coass.entity.Message;
import com.coass.repository.ConversationRepository;
import com.coass.repository.MessageRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/conversations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    // Nowa rozmowa
    @PostMapping
    public ResponseEntity<Map<String, Object>> createConversation(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {

        Conversation conv = chatService.createConversation(projectId, user.getUserId());
        return ResponseEntity.ok(toConvMap(conv, null));
    }

    // Lista rozmów z podglądem ostatniej wiadomości
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listConversations(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {

        List<Map<String, Object>> result = conversationRepository
                .findByProjectIdAndUserIdOrderByCreatedAtDesc(projectId, user.getUserId())
                .stream()
                .map(conv -> {
                    List<Message> last = messageRepository.findLastN(conv.getId(), 1);
                    String preview = last.isEmpty() ? null : last.get(0).getContent();
                    if (preview != null) preview = preview.substring(0, Math.min(100, preview.length()));
                    return toConvMap(conv, preview);
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    // Historia wiadomości w rozmowie
    @GetMapping("/{convId}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable Long projectId,
            @PathVariable Long convId,
            @AuthenticationPrincipal CoassUserDetails user) {

        List<Map<String, Object>> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(convId)
                .stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createdAt", m.getCreatedAt().toString()
                ))
                .toList();

        return ResponseEntity.ok(messages);
    }

    // Wyślij wiadomość w rozmowie
    @PostMapping("/{convId}/messages")
    public ResponseEntity<Map<String, String>> sendMessage(
            @PathVariable Long projectId,
            @PathVariable Long convId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info("HTTP POST /conversations/{}/messages projectId={} userId={}", convId, projectId, user.getUserId());
        String response = chatService.chat(projectId, user.getUserId(), convId, message);
        return ResponseEntity.ok(Map.of("response", response));
    }

    private Map<String, Object> toConvMap(Conversation conv, String lastMessagePreview) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", conv.getId());
        map.put("title", conv.getTitle() != null ? conv.getTitle() : "Nowa rozmowa");
        map.put("createdAt", conv.getCreatedAt().toString());
        if (lastMessagePreview != null) map.put("lastMessage", lastMessagePreview);
        return map;
    }
}
