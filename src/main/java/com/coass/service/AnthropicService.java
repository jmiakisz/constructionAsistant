package com.coass.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicService implements AiChatService {

    private final RestTemplate restTemplate;

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static int toInt(Object val) {
        return val instanceof Number n ? n.intValue() : 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatResponse chat(ChatRequest request) {
        String usedModel = request.modelOverride() != null ? request.modelOverride() : model;

        log.info("=== ANTHROPIC REQUEST === model={} maxTokens={}", usedModel, request.maxTokens());
        if (request.systemPrompt() != null) {
            log.debug("SYSTEM PROMPT:\n{}", request.systemPrompt());
        }
        log.debug("USER MESSAGE:\n{}", request.userMessage());

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", usedModel);
        body.put("max_tokens", request.maxTokens());
        body.put("messages", List.of(Map.of("role", "user", "content", request.userMessage())));

        if (request.cacheableSystemPrompt() != null) {
            // System prompt jako tablica bloków — cacheable + dynamic
            List<Map<String, Object>> systemBlocks = new java.util.ArrayList<>();
            systemBlocks.add(Map.of(
                    "type", "text",
                    "text", request.cacheableSystemPrompt(),
                    "cache_control", Map.of("type", "ephemeral")
            ));
            if (request.systemPrompt() != null) {
                systemBlocks.add(Map.of("type", "text", "text", request.systemPrompt()));
            }
            body.put("system", systemBlocks);
            log.info("Prompt caching enabled — cacheable={}chars dynamic={}chars",
                    request.cacheableSystemPrompt().length(),
                    request.systemPrompt() != null ? request.systemPrompt().length() : 0);
        } else if (request.systemPrompt() != null) {
            body.put("system", request.systemPrompt());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.set("anthropic-beta", "prompt-caching-2024-07-31");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> response = restTemplate.postForObject(
                "https://api.anthropic.com/v1/messages",
                new HttpEntity<>(body, headers),
                Map.class
        );

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        String text = (String) content.get(0).get("text");

        int inputTokens = 0, outputTokens = 0, cacheCreation = 0, cacheRead = 0;
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        if (usage != null) {
            inputTokens    = toInt(usage.get("input_tokens"));
            outputTokens   = toInt(usage.get("output_tokens"));
            cacheCreation  = toInt(usage.get("cache_creation_input_tokens"));
            cacheRead      = toInt(usage.get("cache_read_input_tokens"));
            log.info("=== ANTHROPIC RESPONSE === inputTokens={} outputTokens={} cacheCreation={} cacheRead={}",
                    inputTokens, outputTokens, cacheCreation, cacheRead);
        }
        log.debug("AI RESPONSE:\n{}", text);

        return new ChatResponse(text, inputTokens, outputTokens, cacheCreation, cacheRead);
    }
}
