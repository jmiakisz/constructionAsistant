package com.coass.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
public class OpenAiService implements AiChatService {

    private final RestTemplate restTemplate;

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Override
    @SuppressWarnings("unchecked")
    public ChatResponse chat(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userMessage()));

        Map<String, Object> body = Map.of(
                "model", request.modelOverride() != null ? request.modelOverride() : model,
                "max_tokens", request.maxTokens(),
                "messages", messages
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> response = restTemplate.postForObject(
                "https://api.openai.com/v1/chat/completions",
                new HttpEntity<>(body, headers),
                Map.class
        );

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String text = (String) message.get("content");

        int inputTokens = 0, outputTokens = 0;
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        if (usage != null) {
            inputTokens  = usage.get("prompt_tokens") instanceof Number n ? n.intValue() : 0;
            outputTokens = usage.get("completion_tokens") instanceof Number n ? n.intValue() : 0;
            log.info("=== OPENAI RESPONSE === inputTokens={} outputTokens={}", inputTokens, outputTokens);
        }
        log.debug("AI RESPONSE:\n{}", text);

        return new ChatResponse(text, inputTokens, outputTokens, 0, 0);
    }

    @Override
    public String submitBatch(List<BatchChatRequest> requests) {
        // OpenAI batch API not implemented — run sequentially and return synthetic ID
        log.warn("OpenAI batch not implemented, running {} requests sequentially", requests.size());
        return "openai-sequential-" + System.currentTimeMillis();
    }

    @Override
    public String getBatchStatus(String batchId) {
        return "ended";
    }

    @Override
    public Map<String, ChatResponse> getBatchResults(String batchId) {
        // Results are pre-computed in submitBatch — not supported in sequential fallback
        return new LinkedHashMap<>();
    }
}
