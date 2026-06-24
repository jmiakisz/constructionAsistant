package com.coass.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicService implements AiChatService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String API_BASE = "https://api.anthropic.com/v1";

    // =========================================================
    // Synchronous chat
    // =========================================================
    @Override
    @SuppressWarnings("unchecked")
    public ChatResponse chat(ChatRequest request) {
        String usedModel = request.modelOverride() != null ? request.modelOverride() : model;
        log.info("=== ANTHROPIC REQUEST === model={} maxTokens={}", usedModel, request.maxTokens());

        Map<String, Object> body = buildMessageParams(request, usedModel);

        boolean hasPdf = request.attachments() != null &&
                request.attachments().stream().anyMatch(AttachedFile::isPdf);
        HttpHeaders headers = buildHeaders(true, hasPdf);
        Map<String, Object> response = restTemplate.postForObject(
                API_BASE + "/messages",
                new HttpEntity<>(body, headers),
                Map.class
        );

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        String text = (String) content.get(0).get("text");

        int inputTokens = 0, outputTokens = 0, cacheCreation = 0, cacheRead = 0;
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        if (usage != null) {
            inputTokens   = toInt(usage.get("input_tokens"));
            outputTokens  = toInt(usage.get("output_tokens"));
            cacheCreation = toInt(usage.get("cache_creation_input_tokens"));
            cacheRead     = toInt(usage.get("cache_read_input_tokens"));
            log.info("=== ANTHROPIC RESPONSE === in={} out={} cacheCreate={} cacheRead={}",
                    inputTokens, outputTokens, cacheCreation, cacheRead);
        }
        log.debug("AI RESPONSE:\n{}", text);
        return new ChatResponse(text, inputTokens, outputTokens, cacheCreation, cacheRead);
    }

    // =========================================================
    // Batch API — 50% taniej, asynchroniczne
    // =========================================================
    @Override
    @SuppressWarnings("unchecked")
    public String submitBatch(List<BatchChatRequest> requests) {
        List<Map<String, Object>> batchRequests = new ArrayList<>();
        for (BatchChatRequest req : requests) {
            String usedModel = req.request().modelOverride() != null ? req.request().modelOverride() : model;
            Map<String, Object> params = buildMessageParams(req.request(), usedModel);
            batchRequests.add(Map.of("custom_id", req.customId(), "params", params));
        }

        Map<String, Object> body = Map.of("requests", batchRequests);
        HttpHeaders headers = buildHeaders(false);

        Map<String, Object> response = restTemplate.postForObject(
                API_BASE + "/messages/batches",
                new HttpEntity<>(body, headers),
                Map.class
        );

        String batchId = (String) response.get("id");
        log.info("=== BATCH SUBMITTED === id={} requests={}", batchId, requests.size());
        return batchId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getBatchStatus(String batchId) {
        HttpHeaders headers = buildHeaders(false);
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/messages/batches/" + batchId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        String status = (String) response.getBody().get("processing_status");
        log.debug("Batch {} status: {}", batchId, status);
        return status;
    }

    @Override
    public Map<String, ChatResponse> getBatchResults(String batchId) {
        HttpHeaders headers = buildHeaders(false);
        ResponseEntity<String> response = restTemplate.exchange(
                API_BASE + "/messages/batches/" + batchId + "/results",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        Map<String, ChatResponse> results = new LinkedHashMap<>();
        String[] lines = response.getBody().split("\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String customId = node.path("custom_id").asText();
                JsonNode result = node.path("result");
                if (!"succeeded".equals(result.path("type").asText())) {
                    log.warn("Batch item {} failed: {}", customId, result.path("error").toString());
                    continue;
                }
                JsonNode message = result.path("message");
                String text = message.path("content").get(0).path("text").asText();
                JsonNode usage = message.path("usage");
                results.put(customId, new ChatResponse(
                        text,
                        usage.path("input_tokens").asInt(0),
                        usage.path("output_tokens").asInt(0),
                        usage.path("cache_creation_input_tokens").asInt(0),
                        usage.path("cache_read_input_tokens").asInt(0)
                ));
            } catch (Exception e) {
                log.warn("Failed to parse batch result line: {}", e.getMessage());
            }
        }
        log.info("=== BATCH RESULTS === batchId={} succeeded={}", batchId, results.size());
        return results;
    }

    // =========================================================
    // Helpers
    // =========================================================
    private Map<String, Object> buildMessageParams(ChatRequest request, String usedModel) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", usedModel);
        params.put("max_tokens", request.maxTokens());

        // Historia jako właściwe naprzemienne user/assistant turns
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.history() != null) {
            for (HistoryTurn turn : request.history()) {
                messages.add(Map.of("role", turn.role(), "content", turn.content()));
            }
        }

        // Aktualna wiadomość usera — z załącznikami lub bez
        Object userContent;
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (AttachedFile f : request.attachments()) {
                if (f.isImage()) {
                    blocks.add(Map.of("type", "image", "source", Map.of(
                            "type", "base64", "media_type", f.mediaType(), "data", f.base64Data())));
                } else if (f.isPdf()) {
                    blocks.add(Map.of("type", "document", "source", Map.of(
                            "type", "base64", "media_type", "application/pdf", "data", f.base64Data())));
                }
                log.info("  [ATTACHMENT] {} ({})", f.fileName(), f.mediaType());
            }
            blocks.add(Map.of("type", "text", "text", request.userMessage()));
            userContent = blocks;
        } else {
            userContent = request.userMessage();
        }
        messages.add(Map.of("role", "user", "content", userContent));
        params.put("messages", messages);

        if (request.cacheableSystemPrompt() != null && !request.cacheableSystemPrompt().isBlank()) {
            List<Map<String, Object>> systemBlocks = new ArrayList<>();
            systemBlocks.add(Map.of(
                    "type", "text",
                    "text", request.cacheableSystemPrompt(),
                    "cache_control", Map.of("type", "ephemeral")
            ));
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                systemBlocks.add(Map.of("type", "text", "text", request.systemPrompt()));
            }
            params.put("system", systemBlocks);
        } else if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            params.put("system", request.systemPrompt());
        }
        return params;
    }

    private HttpHeaders buildHeaders(boolean withCaching) {
        return buildHeaders(withCaching, false);
    }

    private HttpHeaders buildHeaders(boolean withCaching, boolean withPdfs) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        List<String> betas = new ArrayList<>();
        if (withCaching) betas.add("prompt-caching-2024-07-31");
        if (withPdfs)    betas.add("pdfs-2024-09-25");
        if (!betas.isEmpty()) headers.set("anthropic-beta", String.join(",", betas));

        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static int toInt(Object val) {
        return val instanceof Number n ? n.intValue() : 0;
    }
}
