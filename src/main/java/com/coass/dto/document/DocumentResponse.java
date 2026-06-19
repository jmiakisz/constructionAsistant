package com.coass.dto.document;

import com.coass.entity.AiIndexingMode;
import com.coass.entity.Document;
import com.coass.entity.DocumentType;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record DocumentResponse(
        Long id,
        String name,
        DocumentType documentType,
        AiIndexingMode aiIndexingMode,
        String status,
        List<String> visibleForRoles,
        Long projectId,
        LocalDateTime createdAt,
        String extractedData
) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getName(),
                d.getDocumentType(),
                d.getAiIndexingMode(),
                d.getStatus(),
                Arrays.asList(d.getVisibleForRoles()),
                d.getProject().getId(),
                d.getCreatedAt(),
                d.getExtractedData()
        );
    }
}
