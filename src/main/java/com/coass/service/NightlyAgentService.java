package com.coass.service;

import com.coass.entity.*;
import com.coass.entity.Role;
import com.coass.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.ArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class NightlyAgentService {

    private final AiChatService aiChatService;
    private final EmbeddingService embeddingService;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final UserStyleObservationRepository styleObservationRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${anthropic.model-extraction:claude-haiku-4-5-20251001}")
    private String haikuModel;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String sonnetModel;

    private static final String AGENT_SYSTEM =
            "Jesteś agentem budowlanym. Odpowiadasz WYŁĄCZNIE poprawnym JSON bez markdown ani backtick.";

    // =========================================================
    // NIGHTLY — codziennie o 2:00
    // =========================================================
    @Scheduled(cron = "0 0 2 * * *")
    public void runNightly() {
        log.info("=== NIGHTLY AGENT START (batch) ===");
        try {
            processKnowledge();           // batch
            consolidateUserStylesBatch(); // batch
            cleanupExpiredKnowledge();    // tylko DB
        } catch (Exception e) {
            log.error("Nightly agent failed", e);
        }
        log.info("=== NIGHTLY AGENT DONE ===");
    }

    // WEEKLY — niedziela o 3:00
    @Scheduled(cron = "0 0 3 * * SUN")
    public void runWeekly() {
        log.info("=== WEEKLY AGENT START ===");
        try {
            consolidateDuplicateKnowledge();
        } catch (Exception e) {
            log.error("Weekly agent failed", e);
        }
        log.info("=== WEEKLY AGENT DONE ===");
    }

    // =========================================================
    // Etap 1+2 — ekstrakcja wiedzy z wiadomości
    // =========================================================

    // Wersja sync (admin/testy) — sekwencyjny chat(), wyniki od razu
    @Transactional
    public void processKnowledgeSync() {
        List<Message> unprocessed = messageRepository.findUnprocessedSince(
                LocalDateTime.now().minusDays(30));
        if (unprocessed.isEmpty()) { log.info("No unprocessed messages"); return; }

        Map<Long, List<Message>> byProject = unprocessed.stream()
                .filter(m -> m.getConversation() != null && m.getConversation().getProject() != null)
                .collect(Collectors.groupingBy(m -> m.getConversation().getProject().getId()));

        String existingKnowledge = knowledgeEntryRepository.findAll().stream()
                .limit(50).map(KnowledgeEntry::getContent)
                .collect(Collectors.joining("\n- ", "- ", ""));

        for (Map.Entry<Long, List<Message>> entry : byProject.entrySet()) {
            Long projectId = entry.getKey();
            List<Message> messages = entry.getValue();
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) continue;

            String convText = messages.stream()
                    .map(m -> m.getRole().toUpperCase() + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));

            ChatResponse haikuResp = aiChatService.chat(ChatRequest.withModel(AGENT_SYSTEM,
                    buildHaikuDeduplicationPrompt(project.getName(), convText, existingKnowledge), haikuModel));
            log.debug("HAIKU DEDUP projectId={} response:\n{}", projectId, haikuResp.text());

            JsonNode haikuNode = parseJson(haikuResp.text());
            if (haikuNode == null || !haikuNode.path("has_new_knowledge").asBoolean(false)) {
                log.info("No new knowledge for project {}", projectId); continue;
            }

            String projectMemory = knowledgeEntryRepository.findByProjectId(projectId).stream()
                    .map(KnowledgeEntry::getContent).collect(Collectors.joining("\n- ", "- ", ""));

            ChatResponse sonnetResp = aiChatService.chat(ChatRequest.withModel(AGENT_SYSTEM,
                    buildSonnetClassificationPrompt(project.getName(), convText,
                            haikuNode.path("new_insights").toString(), projectMemory), sonnetModel));
            log.debug("SONNET classification projectId={} response:\n{}", projectId, sonnetResp.text());

            JsonNode result = parseJson(sonnetResp.text());
            if (result != null) saveKnowledgeEntries(result.path("knowledge_entries"), project, messages);
        }

        unprocessed.forEach(m -> m.setProcessedForKnowledge(true));
        messageRepository.saveAll(unprocessed);
        log.info("Marked {} messages as processed", unprocessed.size());
    }

    // Wersja batch (nightly scheduler) — 50% taniej, asynchroniczne
    @Transactional
    public void processKnowledge() {
        List<Message> unprocessed = messageRepository.findUnprocessedSince(
                LocalDateTime.now().minusDays(2));

        if (unprocessed.isEmpty()) {
            log.info("No unprocessed messages");
            return;
        }
        log.info("Processing {} unprocessed messages", unprocessed.size());

        Map<Long, List<Message>> byProject = unprocessed.stream()
                .filter(m -> m.getConversation() != null && m.getConversation().getProject() != null)
                .collect(Collectors.groupingBy(m -> m.getConversation().getProject().getId()));

        // Etap 1 — BATCH: wszystkie Haiku dedup naraz (jeden API call)
        String existingKnowledge = knowledgeEntryRepository.findAll().stream()
                .limit(50)
                .map(KnowledgeEntry::getContent)
                .collect(Collectors.joining("\n- ", "- ", ""));

        Map<Long, Project> projects = new LinkedHashMap<>();
        Map<Long, String> conversationTexts = new LinkedHashMap<>();
        List<BatchChatRequest> haikuBatch = new ArrayList<>();

        for (Map.Entry<Long, List<Message>> entry : byProject.entrySet()) {
            Long projectId = entry.getKey();
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) continue;

            String convText = entry.getValue().stream()
                    .map(m -> m.getRole().toUpperCase() + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));

            projects.put(projectId, project);
            conversationTexts.put(projectId, convText);

            String prompt = buildHaikuDeduplicationPrompt(project.getName(), convText, existingKnowledge);
            haikuBatch.add(new BatchChatRequest("haiku-" + projectId,
                    ChatRequest.withModel(AGENT_SYSTEM, prompt, haikuModel)));
        }

        if (haikuBatch.isEmpty()) return;

        log.info("BATCH submitting {} Haiku dedup requests", haikuBatch.size());
        String haikuBatchId = aiChatService.submitBatch(haikuBatch);
        Map<String, ChatResponse> haikuResults = pollBatch(haikuBatchId);

        // Etap 2 — BATCH: Sonnet tylko dla projektów z nową wiedzą
        List<BatchChatRequest> sonnetBatch = new ArrayList<>();
        Map<Long, JsonNode> haikuParsed = new LinkedHashMap<>();

        for (Long projectId : projects.keySet()) {
            ChatResponse haikuResp = haikuResults.get("haiku-" + projectId);
            if (haikuResp == null) continue;
            log.debug("HAIKU DEDUP projectId={} response:\n{}", projectId, haikuResp.text());

            JsonNode haikuNode = parseJson(haikuResp.text());
            if (haikuNode == null || !haikuNode.path("has_new_knowledge").asBoolean(false)) {
                log.info("No new knowledge for project {}", projectId);
                continue;
            }
            haikuParsed.put(projectId, haikuNode);

            String projectMemory = knowledgeEntryRepository.findByProjectId(projectId).stream()
                    .map(KnowledgeEntry::getContent)
                    .collect(Collectors.joining("\n- ", "- ", ""));

            String prompt = buildSonnetClassificationPrompt(
                    projects.get(projectId).getName(),
                    conversationTexts.get(projectId),
                    haikuNode.path("new_insights").toString(),
                    projectMemory);

            sonnetBatch.add(new BatchChatRequest("sonnet-" + projectId,
                    ChatRequest.withModel(AGENT_SYSTEM, prompt, sonnetModel)));
        }

        if (!sonnetBatch.isEmpty()) {
            log.info("BATCH submitting {} Sonnet classification requests", sonnetBatch.size());
            String sonnetBatchId = aiChatService.submitBatch(sonnetBatch);
            Map<String, ChatResponse> sonnetResults = pollBatch(sonnetBatchId);

            for (Long projectId : haikuParsed.keySet()) {
                ChatResponse sonnetResp = sonnetResults.get("sonnet-" + projectId);
                if (sonnetResp == null) continue;
                log.debug("SONNET classification projectId={} response:\n{}", projectId, sonnetResp.text());

                JsonNode result = parseJson(sonnetResp.text());
                if (result == null) continue;
                saveKnowledgeEntries(result.path("knowledge_entries"), projects.get(projectId), byProject.get(projectId));
            }
        }

        // oznacz jako przetworzone
        unprocessed.forEach(m -> m.setProcessedForKnowledge(true));
        messageRepository.saveAll(unprocessed);
        log.info("Marked {} messages as processed", unprocessed.size());
    }

    private Map<String, ChatResponse> pollBatch(String batchId) {
        log.info("Polling batch {} ...", batchId);
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String status = aiChatService.getBatchStatus(batchId);
            log.info("Batch {} status: {}", batchId, status);
            if ("ended".equals(status)) break;
        }
        return aiChatService.getBatchResults(batchId);
    }

    private void saveKnowledgeEntries(JsonNode entries, Project project, List<Message> messages) {
        if (!entries.isArray()) return;

        String sourceRole = messages.stream()
                .filter(m -> m.getConversation() != null)
                .map(m -> {
                    Long userId = m.getConversation().getUser().getId();
                    return projectMemberRepository
                            .findRoleByProjectAndUser(project.getId(), userId)
                            .map(Role::name)
                            .orElse("MEMBER");
                })
                .findFirst().orElse("MEMBER");

        for (JsonNode entry : entries) {
            try {
                String content = entry.path("content").asText();
                String category = entry.path("category").asText("TECHNICZNA");
                String entryType = entry.path("type").asText("PERMANENT");
                String validUntilStr = entry.path("valid_until").isNull() ? null : entry.path("valid_until").asText(null);
                Long projectId = entry.path("project_specific").asBoolean(false) ? project.getId() : null;

                float[] vec = embeddingService.embed(content);
                String vecStr = embeddingService.toVectorString(vec);

                final String fContent = content, fCategory = category, fEntryType = entryType,
                        fValidUntil = validUntilStr, fSourceRole = sourceRole, fVecStr = vecStr;
                final Long fProjectId = projectId;

                transactionTemplate.execute(status -> {
                    knowledgeEntryRepository.insertWithEmbedding(
                            fProjectId, fContent, fVecStr, fSourceRole, fCategory, fEntryType, fValidUntil);
                    return null;
                });

                log.info("DB INSERT knowledge_entries type={} category={} projectId={} content='{}'",
                        entryType, category, projectId, content.substring(0, Math.min(80, content.length())));
            } catch (Exception e) {
                log.error("Failed to save knowledge entry", e);
            }
        }
    }

    // =========================================================
    // Konsolidacja stylu — SYNC (admin/testy)
    // =========================================================
    @Transactional
    public void consolidateUserStylesSync() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<User> users = userRepository.findAll();

        for (User user : users) {
            List<UserStyleObservation> observations = styleObservationRepository.findRecentByUserId(user.getId(), since);
            if (observations.size() < 3) continue;

            log.info("Consolidating style for user {} — {} observations", user.getId(), observations.size());

            String obsList = observations.stream()
                    .map(UserStyleObservation::getObservation)
                    .collect(Collectors.joining("\n- ", "- ", ""));

            ChatResponse haikuResp = aiChatService.chat(ChatRequest.withModel(AGENT_SYSTEM,
                    buildStyleHaikuPrompt(obsList), haikuModel));
            JsonNode haikuResult = parseJson(haikuResp.text());
            if (haikuResult == null) continue;

            ChatResponse sonnetResp = aiChatService.chat(ChatRequest.withModel(AGENT_SYSTEM,
                    buildStyleSonnetPrompt(user.getName(), haikuResult.path("meaningful_observations").toString()), sonnetModel));
            JsonNode profile = parseJson(sonnetResp.text());
            if (profile == null) continue;

            saveUserStyle(user, profile);
        }

        cleanupStyleObservations();
    }

    // =========================================================
    // Konsolidacja stylu — BATCH (nightly scheduler)
    // =========================================================
    public void consolidateUserStylesBatch() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<User> users = userRepository.findAll();

        Map<Long, User> eligibleUsers = new LinkedHashMap<>();
        List<BatchChatRequest> haikuBatch = new ArrayList<>();

        for (User user : users) {
            List<UserStyleObservation> obs = styleObservationRepository.findRecentByUserId(user.getId(), since);
            if (obs.size() < 3) continue;
            String obsList = obs.stream().map(UserStyleObservation::getObservation)
                    .collect(Collectors.joining("\n- ", "- ", ""));
            eligibleUsers.put(user.getId(), user);
            haikuBatch.add(new BatchChatRequest("style-haiku-" + user.getId(),
                    ChatRequest.withModel(AGENT_SYSTEM, buildStyleHaikuPrompt(obsList), haikuModel)));
        }

        if (haikuBatch.isEmpty()) { cleanupStyleObservations(); return; }

        log.info("BATCH submitting {} style Haiku requests", haikuBatch.size());
        Map<String, ChatResponse> haikuResults = pollBatch(aiChatService.submitBatch(haikuBatch));

        List<BatchChatRequest> sonnetBatch = new ArrayList<>();
        Map<Long, JsonNode> haikuParsed = new LinkedHashMap<>();

        for (Long userId : eligibleUsers.keySet()) {
            ChatResponse resp = haikuResults.get("style-haiku-" + userId);
            if (resp == null) continue;
            JsonNode node = parseJson(resp.text());
            if (node == null) continue;
            haikuParsed.put(userId, node);
            sonnetBatch.add(new BatchChatRequest("style-sonnet-" + userId,
                    ChatRequest.withModel(AGENT_SYSTEM,
                            buildStyleSonnetPrompt(eligibleUsers.get(userId).getName(),
                                    node.path("meaningful_observations").toString()), sonnetModel)));
        }

        if (!sonnetBatch.isEmpty()) {
            log.info("BATCH submitting {} style Sonnet requests", sonnetBatch.size());
            Map<String, ChatResponse> sonnetResults = pollBatch(aiChatService.submitBatch(sonnetBatch));

            for (Long userId : haikuParsed.keySet()) {
                ChatResponse resp = sonnetResults.get("style-sonnet-" + userId);
                if (resp == null) continue;
                JsonNode profile = parseJson(resp.text());
                if (profile == null) continue;
                transactionTemplate.execute(status -> {
                    saveUserStyle(eligibleUsers.get(userId), profile);
                    return null;
                });
            }
        }

        cleanupStyleObservations();
    }

    private void saveUserStyle(User user, JsonNode profile) {
        user.setCommunicationStyle(profile.path("communication_style").asText(null));
        user.setFormalityLevel(profile.path("formality_level").asText("NEUTRAL"));
        userRepository.save(user);
        log.info("DB UPDATE users id={} formalityLevel={}", user.getId(), user.getFormalityLevel());
    }

    public void cleanupStyleObservations() {
        transactionTemplate.execute(status -> {
            styleObservationRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(30));
            return null;
        });
        log.info("Cleaned old style observations");
    }

    private String buildStyleHaikuPrompt(String obsList) {
        return """
                Poniżej obserwacje stylu komunikacji użytkownika. Odrzuć jednorazowe anomalie i szum.
                Zostaw tylko powtarzające się cechy. Zwróć JSON:
                {"meaningful_observations": ["obserwacja1", "obserwacja2"]}

                OBSERWACJE:
                %s
                """.formatted(obsList);
    }

    private String buildStyleSonnetPrompt(String userName, String observations) {
        return """
                Na podstawie obserwacji stwórz spójny profil komunikacyjny użytkownika %s.
                Zwróć JSON:
                {"communication_style": "opis stylu (1-2 zdania)", "formality_level": "FORMAL|NEUTRAL|CASUAL"}

                OBSERWACJE:
                %s
                """.formatted(userName, observations);
    }

    // =========================================================
    // Cleanup expired temporal knowledge
    // =========================================================
    public void cleanupExpiredKnowledge() {
        transactionTemplate.execute(status -> {
            int deleted = knowledgeEntryRepository.deleteExpired(LocalDateTime.now());
            if (deleted > 0) log.info("DB DELETE knowledge_entries expired={}", deleted);

            int degraded = knowledgeEntryRepository.degradeOldEntries(LocalDateTime.now().minusDays(60));
            if (degraded > 0) log.info("DB UPDATE knowledge_entries degraded confidence count={}", degraded);

            int removed = knowledgeEntryRepository.deleteZeroConfidence();
            if (removed > 0) log.info("DB DELETE knowledge_entries zero confidence count={}", removed);

            return null;
        });
    }

    // =========================================================
    // Weekly — konsolidacja duplikatów
    // =========================================================
    @Transactional
    public void consolidateDuplicateKnowledge() {
        log.info("Weekly knowledge consolidation starting");
        // similarity > 0.85 w pgvector — grupujemy i mergujemy
        List<Object[]> duplicates = knowledgeEntryRepository.findPotentialDuplicates(0.15); // cosine distance < 0.15

        Set<Long> merged = new HashSet<>();
        for (Object[] pair : duplicates) {
            Long id1 = ((Number) pair[0]).longValue();
            Long id2 = ((Number) pair[1]).longValue();
            if (merged.contains(id1) || merged.contains(id2)) continue;

            knowledgeEntryRepository.findById(id1).ifPresent(ke1 -> {
                knowledgeEntryRepository.findById(id2).ifPresent(ke2 -> {
                    ke1.setConfidence(ke1.getConfidence() + ke2.getConfidence());
                    ke1.setLastConfirmedAt(LocalDateTime.now());
                    knowledgeEntryRepository.save(ke1);
                    knowledgeEntryRepository.delete(ke2);
                    log.info("Merged knowledge_entries id={} into id={}", id2, id1);
                });
            });
            merged.add(id2);
        }
        log.info("Weekly consolidation done — merged {} entries", merged.size());
    }

    // =========================================================
    // Prompt builders
    // =========================================================
    private String buildHaikuDeduplicationPrompt(String projectName, String conversations, String existingKnowledge) {
        return """
                Projekt: %s
                Przeanalizuj rozmowy i sprawdź czy zawierają nową wiedzę której nie ma w bazie.

                ISTNIEJĄCA WIEDZA FIRMOWA:
                %s

                NOWE ROZMOWY:
                %s

                Zwróć JSON:
                {
                  "has_new_knowledge": true/false,
                  "new_insights": ["insight1", "insight2"]
                }
                """.formatted(projectName, existingKnowledge, conversations);
    }

    private String buildSonnetClassificationPrompt(String projectName, String conversations,
                                                    String newInsights, String currentProjectMemory) {
        return """
                Projekt: %s
                Wyodrębnij i sklasyfikuj wiedzę z rozmów. Weryfikuj każdy fakt z ORYGINALNĄ ROZMOWĄ — nie ufaj ślepo wstępnym wnioskom, mogą być błędne.

                AKTUALNA PAMIĘĆ PROJEKTU:
                %s

                WSTĘPNE WNIOSKI (do weryfikacji):
                %s

                ORYGINALNA ROZMOWA (źródło prawdy):
                %s

                Zwróć JSON:
                {
                  "knowledge_entries": [
                    {
                      "content": "max 500 znaków, dokładny fakt przepisany z rozmowy",
                      "category": "TECHNICZNA|FINANSOWA|PODWYKONAWCY|MATERIALY",
                      "type": "PERMANENT|TEMPORAL",
                      "valid_until": "2026-07-01T00:00:00" lub null,
                      "project_specific": true
                    }
                  ]
                }

                Zasady:
                - ZAWSZE weryfikuj daty, godziny, kwoty i nazwy z oryginalną rozmową
                - Rozróżniaj godzinę (np. "13:00", "godzina 13") od dnia miesiąca ("13-ty", "13 czerwca")
                - type=TEMPORAL gdy wiedza dotyczy konkretnej daty/terminu (ustaw valid_until)
                - type=PERMANENT dla wzorców, cech, reguł ogólnych
                - project_specific=true dla ustaleń tej konkretnej budowy, false dla wiedzy ogólnofirmowej
                - Daty absolutne, nigdy względne ("za 3 dni" → konkretna data)
                """.formatted(projectName, currentProjectMemory, newInsights, conversations);
    }

    // =========================================================
    // Helpers
    // =========================================================
    private JsonNode parseJson(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return objectMapper.readTree(text.substring(start, end + 1));
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON: {}", e.getMessage());
        }
        return null;
    }
}
