package com.coass.dto.document;

import com.coass.entity.AiIndexingMode;
import com.coass.entity.Document;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record DocumentResponse(
        Long id,
        String name,
        String documentType,
        AiIndexingMode aiIndexingMode,
        String status,
        List<String> visibleForRoles,
        Long projectId,
        Long folderId,
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
                d.getFolder() != null ? d.getFolder().getId() : null,
                d.getCreatedAt(),
                d.getExtractedData()
        );
    }
}
