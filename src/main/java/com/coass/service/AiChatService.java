package com.coass.service;

import java.util.List;
import java.util.Map;

public interface AiChatService {
    ChatResponse chat(ChatRequest request);

    // Zwraca batchId
    String submitBatch(List<BatchChatRequest> requests);

    // "in_progress" | "ended"
    String getBatchStatus(String batchId);

    // customId → ChatResponse
    Map<String, ChatResponse> getBatchResults(String batchId);
}
