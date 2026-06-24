package com.coass.service;

public record ChatRequest(
        String cacheableSystemPrompt,
        String systemPrompt,
        String userMessage,
        int maxTokens,
        String modelOverride,
        java.util.List<AttachedFile> attachments,
        java.util.List<HistoryTurn> history
) {
    public static ChatRequest of(String systemPrompt, String userMessage) {
        return new ChatRequest(null, systemPrompt, userMessage, 4096, null, null, null);
    }

    public static ChatRequest withCache(String cacheableSystemPrompt, String dynamicSystemPrompt, String userMessage) {
        return new ChatRequest(cacheableSystemPrompt, dynamicSystemPrompt, userMessage, 4096, null, null, null);
    }

    public static ChatRequest withCache(String cacheableSystemPrompt, String dynamicSystemPrompt, String userMessage,
                                        java.util.List<AttachedFile> attachments,
                                        java.util.List<HistoryTurn> history) {
        return new ChatRequest(cacheableSystemPrompt, dynamicSystemPrompt, userMessage, 4096, null, attachments, history);
    }

    public static ChatRequest user(String userMessage) {
        return new ChatRequest(null, null, userMessage, 4096, null, null, null);
    }

    public static ChatRequest withModel(String systemPrompt, String userMessage, String model) {
        return new ChatRequest(null, systemPrompt, userMessage, 4096, model, null, null);
    }

    public ChatRequest withModelOverride(String model) {
        return new ChatRequest(cacheableSystemPrompt(), systemPrompt(), userMessage(), maxTokens(), model, attachments(), history());
    }
}
