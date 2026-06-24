package com.coass.controller;

import com.coass.entity.Conversation;
import com.coass.entity.Message;
import com.coass.repository.ConversationRepository;
import com.coass.repository.MessageRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.AttachedFile;
import com.coass.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
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

        Conversation conv = conversationRepository.findById(convId).orElse(null);
        if (conv == null || !conv.getUser().getId().equals(user.getUserId())) {
            return ResponseEntity.status(403).build();
        }

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

    // Wyślij wiadomość — JSON (bez plików)
    @PostMapping(value = "/{convId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sendMessage(
            @PathVariable Long projectId,
            @PathVariable Long convId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        log.info("HTTP POST /conversations/{}/messages projectId={} userId={}", convId, projectId, user.getUserId());
        ChatService.ChatResult result = chatService.chat(projectId, user.getUserId(), convId, message, List.of());
        return ResponseEntity.ok(buildResponse(result));
    }

    // Wyślij wiadomość — multipart (z załącznikami)
    @PostMapping(value = "/{convId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> sendMessageWithFiles(
            @PathVariable Long projectId,
            @PathVariable Long convId,
            @RequestPart("message") String message,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal CoassUserDetails user) {

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        List<AttachedFile> attachments = List.of();
        if (files != null && !files.isEmpty()) {
            attachments = files.stream()
                    .filter(f -> !f.isEmpty())
                    .map(f -> {
                        try {
                            String mediaType = resolveMediaType(f);
                            String base64 = Base64.getEncoder().encodeToString(f.getBytes());
                            log.info("  [ATTACH] {} {} {}KB", f.getOriginalFilename(), mediaType, f.getSize() / 1024);
                            return new AttachedFile(f.getOriginalFilename(), mediaType, base64);
                        } catch (Exception e) {
                            log.warn("Failed to read attachment {}: {}", f.getOriginalFilename(), e.getMessage());
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        log.info("HTTP POST /conversations/{}/messages projectId={} userId={} attachments={}",
                convId, projectId, user.getUserId(), attachments.size());
        ChatService.ChatResult result = chatService.chat(projectId, user.getUserId(), convId, message, attachments);
        return ResponseEntity.ok(buildResponse(result));
    }

    private Map<String, Object> buildResponse(ChatService.ChatResult result) {
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("response", result.response());
        if (result.document() != null) {
            Map<String, String> doc = new java.util.LinkedHashMap<>();
            doc.put("title", result.document().title());
            doc.put("content", result.document().content());
            resp.put("document", doc);
        }
        return resp;
    }

    private String resolveMediaType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null && !ct.isBlank() && !"application/octet-stream".equals(ct)) return ct;
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (name.endsWith(".pdf"))  return "application/pdf";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif"))  return "image/gif";
        return ct != null ? ct : "application/octet-stream";
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
