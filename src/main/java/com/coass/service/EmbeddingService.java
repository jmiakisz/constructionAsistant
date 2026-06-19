package com.coass.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestTemplate restTemplate;

    @Value("${app.embedding-service-url:http://localhost:8001}")
    private String embeddingServiceUrl;

    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, String> request = Map.of("text", text);
        Map<String, Object> response = restTemplate.postForObject(
                embeddingServiceUrl + "/embed", request, Map.class);

        List<Double> embedding = (List<Double>) response.get("embedding");
        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }
        return result;
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
