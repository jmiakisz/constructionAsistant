package com.coass.service;

import com.coass.entity.*;
import com.coass.repository.DocumentRepository;
import com.coass.repository.ProjectAlertRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAnalysisService {

    private final AiChatService aiChatService;
    private final DocumentRepository documentRepository;
    private final ProjectAlertRepository alertRepository;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.model-extraction:claude-haiku-4-5-20251001}")
    private String extractionModel;

    private static final String SYSTEM_PROMPT =
            "Jesteś asystentem prawno-budowlanym. Odpowiadasz WYŁĄCZNIE poprawnym JSON bez markdown ani backtick.";

    @Transactional
    public void analyzeDocument(Long documentId, String text) {
        Document doc = documentRepository.findById(documentId).orElseThrow();
        try {
            log.info("=== ANALYSIS START === documentId={} name='{}' type={} textLength={}",
                    documentId, doc.getName(), doc.getDocumentType(), text.length());

            String userMessage = buildDocumentUserMessage(doc.getDocumentType(), text);
            log.debug("ANALYSIS PROMPT:\n{}", userMessage);
            ChatResponse resp = aiChatService.chat(ChatRequest.withModel(SYSTEM_PROMPT, userMessage, extractionModel));
            log.info("ANALYSIS TOKENS === input={} output={}", resp.inputTokens(), resp.outputTokens());
            log.debug("ANALYSIS RAW RESPONSE:\n{}", resp.text());
            String cleanJson = extractJson(resp.text());

            doc.setExtractedData(cleanJson);
            documentRepository.save(doc);
            log.info("DB UPDATE documents id={} extracted_data saved ({}chars)", documentId, cleanJson.length());
            log.debug("DB UPDATE documents id={} extracted_data:\n{}", documentId, cleanJson);

            saveDocumentAlerts(doc, cleanJson);

            // #1 — cross analiza tylko gdy wszystkie dokumenty FULL są już przetworzone
            long stillProcessing = documentRepository.countByProjectIdAndAiIndexingModeAndStatus(
                    doc.getProject().getId(), AiIndexingMode.FULL, DocumentStatus.PROCESSING.name());
            if (stillProcessing == 0) {
                runCrossAnalysis(doc.getProject().getId(), doc);
            } else {
                log.info("Cross analysis deferred — {} documents still processing", stillProcessing);
            }

        } catch (Exception e) {
            log.error("Error analyzing document {}", documentId, e);
        }
    }

    private void saveDocumentAlerts(Document doc, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            saveAlerts(doc, root.path("ryzyka"), "WARNING");
            saveAlerts(doc, root.path("wewnetrzne_niespojnosci"), "WARNING");
        } catch (Exception e) {
            log.error("Error saving document alerts for doc {}", doc.getId(), e);
        }
    }

    private void saveAlerts(Document doc, JsonNode items, String defaultLevel) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            ProjectAlert pa = new ProjectAlert();
            pa.setProject(doc.getProject());
            pa.setDocument(doc);
            pa.setLevel(item.has("level") ? item.path("level").asText(defaultLevel) : defaultLevel);
            pa.setMessage(item.isTextual() ? item.asText() : item.path("opis").asText(item.toString()));
            ProjectAlert saved = alertRepository.save(pa);
            log.info("DB INSERT project_alerts id={} level={} documentId={} message='{}'",
                    saved.getId(), saved.getLevel(), doc.getId(), saved.getMessage());
        }
    }

    // #2 — Haiku do cross analizy (porównuje małe JSONy, nie potrzebuje Sonnet)
    private void runCrossAnalysis(Long projectId, Document triggerDoc) {
        List<Document> docs = documentRepository.findByProjectIdWithExtractedData(projectId).stream()
                .filter(d -> triggerDoc.getDocumentType().equals(d.getDocumentType()))
                .toList();
        if (docs.size() < 2) return;

        try {
            StringBuilder sb = new StringBuilder();
            for (Document d : docs) {
                sb.append("Dokument: ").append(d.getName())
                  .append(" (").append(d.getDocumentType()).append(")\n")
                  .append(d.getExtractedData()).append("\n\n");
            }

            String userMessage = buildCrossAnalysisUserMessage(sb.toString());
            log.info("=== CROSS ANALYSIS START === projectId={} documents={}", projectId, docs.size());
            log.debug("CROSS ANALYSIS PROMPT:\n{}", userMessage);

            ChatResponse resp = aiChatService.chat(ChatRequest.withModel(SYSTEM_PROMPT, userMessage, extractionModel));
            log.info("CROSS ANALYSIS TOKENS === input={} output={}", resp.inputTokens(), resp.outputTokens());
            log.debug("CROSS ANALYSIS RAW RESPONSE:\n{}", resp.text());
            String cleanJson = extractJson(resp.text());

            JsonNode root = objectMapper.readTree(cleanJson);
            JsonNode alerts = root.path("alerts");

            alertRepository.deleteByProjectId(projectId);
            log.info("DB DELETE project_alerts projectId={} (refresh)", projectId);

            if (alerts.isArray()) {
                for (JsonNode alert : alerts) {
                    ProjectAlert pa = new ProjectAlert();
                    pa.setProject(triggerDoc.getProject());
                    pa.setDocument(null); // cross-analysis alerts are project-level
                    pa.setLevel(alert.path("level").asText("INFO"));
                    pa.setMessage(alert.path("message").asText());
                    ProjectAlert saved = alertRepository.save(pa);
                    log.info("DB INSERT project_alerts id={} level={} message='{}'",
                            saved.getId(), saved.getLevel(), saved.getMessage());
                }
            }
            log.info("Cross analysis done for project {}: {} alerts saved", projectId, alerts.size());

        } catch (Exception e) {
            log.error("Error in cross analysis for project {}", projectId, e);
        }
    }

    private String buildDocumentUserMessage(String type, String text) {
        return """
                Przeanalizuj poniższy dokument typu %s.

                Format odpowiedzi:
                {
                  "extracted_data": {
                    // dowolne klucze i wartości które faktycznie występują w dokumencie
                    // np. "wartosc_netto": "1234.56 PLN", "termin_platnosci": "2026-07-01", "strony": ["Firma A", "Firma B"]
                    // nie wymyślaj pól których nie ma - wyciągaj tylko to co realnie jest w dokumencie
                  },
                  "wewnetrzne_niespojnosci": [
                    "opis niespójności jeśli istnieje"
                  ],
                  "ryzyka": [
                    "opis ryzyka jeśli istnieje"
                  ]
                }

                DOKUMENT:
                %s
                """.formatted(type, text);
    }

    private String buildCrossAnalysisUserMessage(String extractedDataList) {
        return """
                Przeanalizuj dane wyciągnięte z dokumentów projektu. Znajdź niespójności i ryzyka między dokumentami.

                Format odpowiedzi:
                {
                  "alerts": [
                    {"level": "CRITICAL", "message": "..."},
                    {"level": "WARNING", "message": "..."},
                    {"level": "INFO", "message": "..."}
                  ]
                }

                Poziomy: CRITICAL (ryzyko finansowe/prawne), WARNING (niespójność), INFO (obserwacja).

                DANE Z DOKUMENTÓW:
                %s
                """.formatted(extractedDataList);
    }

    private String extractJson(String text) {
        text = text.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
