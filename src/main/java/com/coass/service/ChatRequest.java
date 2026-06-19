package com.coass.service;

public record ChatRequest(
        String cacheableSystemPrompt,  // statyczne: kontekst, pamięć projektu, wiedza firmowa
        String systemPrompt,           // dynamiczne: RAG chunks (nie keszujemy)
        String userMessage,
        int maxTokens,
        String modelOverride
) {
    public static ChatRequest of(String systemPrompt, String userMessage) {
        return new ChatRequest(null, systemPrompt, userMessage, 4096, null);
    }

    public static ChatRequest withCache(String cacheableSystemPrompt, String dynamicSystemPrompt, String userMessage) {
        return new ChatRequest(cacheableSystemPrompt, dynamicSystemPrompt, userMessage, 4096, null);
    }

    public static ChatRequest user(String userMessage) {
        return new ChatRequest(null, null, userMessage, 4096, null);
    }

    public static ChatRequest withModel(String systemPrompt, String userMessage, String model) {
        return new ChatRequest(null, systemPrompt, userMessage, 4096, model);
    }

    public ChatRequest withModelOverride(String model) {
        return new ChatRequest(cacheableSystemPrompt(), systemPrompt(), userMessage(), maxTokens(), model);
    }
}
