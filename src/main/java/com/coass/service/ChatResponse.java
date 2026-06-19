package com.coass.service;

public record ChatResponse(
        String text,
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens
) {
    public static ChatResponse of(String text) {
        return new ChatResponse(text, 0, 0, 0, 0);
    }
}
