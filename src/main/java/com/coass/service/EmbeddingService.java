package com.coass.service;

import com.coass.entity.EmbeddingUsage;
import com.coass.entity.Project;
import com.coass.repository.EmbeddingUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestTemplate restTemplate;
    private final EmbeddingUsageRepository usageRepository;

    @Value("${app.embedding-service-url:http://localhost:8001}")
    private String embeddingServiceUrl;

    public float[] embed(String text) {
        return embed(text, "UNKNOWN", null);
    }

    public float[] embed(String text, String source, Project project) {
        Map<String, String> request = Map.of("text", text);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                embeddingServiceUrl + "/embed", request, Map.class);

        List<Double> embedding = (List<Double>) response.get("embedding");
        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }

        saveUsageAsync(source, text.length(), project);
        return result;
    }

    @Async
    protected void saveUsageAsync(String source, int textLength, Project project) {
        try {
            EmbeddingUsage usage = new EmbeddingUsage();
            usage.setSource(source);
            usage.setTextLength(textLength);
            usage.setProject(project);
            usageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to save embedding usage: {}", e.getMessage());
        }
    }

    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
