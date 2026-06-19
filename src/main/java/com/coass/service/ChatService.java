package com.coass.service;

import com.coass.entity.*;
import com.coass.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AiChatService aiChatService;
    private final EmbeddingService embeddingService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final UserStyleObservationRepository styleObservationRepository;

    @Value("${anthropic.model-chat:claude-haiku-4-5-20251001}")
    private String chatModel;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String escalateModel;

    private static final int HISTORY_LIMIT = 10;
    private static final int RAG_CHUNKS = 5;
    private static final int RAG_KNOWLEDGE = 5;

    @Transactional
    public Conversation createConversation(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        return createConversation(project, user);
    }

    @Transactional
    public String chat(Long projectId, Long userId, Long conversationId, String userMessage) {
        User user = userRepository.findById(userId).orElseThrow();
        Project project = projectRepository.findById(projectId).orElseThrow();
        String userRole = resolveProjectRole(user, project);

        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();

        // Auto-title z pierwszej wiadomości
        if (conversation.getTitle() == null && !userMessage.isBlank()) {
            conversation.setTitle(userMessage.substring(0, Math.min(60, userMessage.length())));
            conversationRepository.save(conversation);
        }

        log.info("=== CHAT REQUEST === projectId={} userId={} role={} message='{}'",
                projectId, userId, userRole, userMessage);

        String cacheablePrompt = buildCacheablePrompt(project, user, userRole);
        String dynamicPrompt = buildDynamicPrompt(project, userRole, userMessage);
        String historyBlock = buildHistory(conversation);
        String fullUserMessage = historyBlock.isBlank()
                ? userMessage
                : historyBlock + "\n\nUSER: " + userMessage;

        log.debug("CACHEABLE SYSTEM PROMPT:\n{}", cacheablePrompt);
        log.debug("DYNAMIC SYSTEM PROMPT:\n{}", dynamicPrompt);
        log.debug("FULL USER MESSAGE (with history):\n{}", fullUserMessage);

        // #3 — Haiku odpowiada, jeśli escalate=true → Sonnet
        ChatResponse haikusResp = aiChatService.chat(
                ChatRequest.withCache(cacheablePrompt, dynamicPrompt, fullUserMessage)
                        .withModelOverride(chatModel));
        log.debug("HAIKU RAW RESPONSE:\n{}", haikusResp.text());

        ParsedChatResponse parsed = parseResponse(haikusResp.text());
        ChatResponse finalResp = haikusResp;
        String usedModel = chatModel;

        if (parsed.escalate()) {
            log.info("=== ESCALATING TO SONNET === projectId={} userId={}", projectId, userId);
            finalResp = aiChatService.chat(
                    ChatRequest.withCache(cacheablePrompt, dynamicPrompt, fullUserMessage)
                            .withModelOverride(escalateModel));
            parsed = parseResponse(finalResp.text());
            usedModel = escalateModel;
            log.debug("SONNET RAW RESPONSE:\n{}", finalResp.text());
        }

        log.info("=== CHAT RESPONSE === projectId={} userId={} model={} valuable={} escalated={} in={} out={}",
                projectId, userId, usedModel, parsed.valuable(), !usedModel.equals(chatModel),
                finalResp.inputTokens(), finalResp.outputTokens());

        saveMessage(conversation, "user", userMessage, parsed.valuable(), 0, 0, null, parsed.category());
        saveMessage(conversation, "assistant", parsed.response(), parsed.valuable(),
                finalResp.inputTokens(), finalResp.outputTokens(), usedModel, parsed.category());

        if (parsed.styleObservation() != null) {
            saveStyleObservation(user, parsed.styleObservation());
        }
        if (!parsed.valuable()) {
            log.info("Messages saved but marked processedForKnowledge=true (not valuable for knowledge extraction)");
        }

        return parsed.response();
    }

    // Warstwa 1 — statyczna, keszowana przez Anthropic przez 5 min
    private String buildCacheablePrompt(Project project, User user, String userRole) {
        StringBuilder sb = new StringBuilder();

        sb.append("Jesteś asystentem budowlanym projektu \"").append(project.getName()).append("\".\n");
        sb.append("Rozmawiasz z użytkownikiem o roli: ").append(userRole).append(", imię: ").append(user.getName()).append(".\n");
        if (user.getCommunicationStyle() != null && !user.getCommunicationStyle().isBlank()) {
            sb.append("Styl komunikacji użytkownika: ").append(user.getCommunicationStyle())
              .append(" (poziom formalności: ").append(user.getFormalityLevel()).append(").\n");
            sb.append("Dopasuj ton i formę odpowiedzi do tego stylu.\n");
        }
        sb.append("Odpowiadasz po polsku, konkretnie i na temat projektu.\n\n");
        sb.append("WAŻNE: Odpowiedź zwróć WYŁĄCZNIE jako JSON bez markdown:\n");
        sb.append("{\"response\": \"twoja odpowiedź\", \"valuable\": true/false, \"escalate\": false, \"category\": null, \"style_observation\": null}\n");
        sb.append("valuable=true gdy odpowiedź zawiera konkretną wiedzę: ceny, kwoty, daty, nazwy firm/podwykonawców, decyzje, ustalenia, problemy techniczne, dane z dokumentów, ryzyka, terminy.\n");
        sb.append("valuable=false gdy: powitanie/pożegnanie/podziękowanie, prośba o doprecyzowanie, ogólna rozmowa o projekcie BEZ konkretnych faktów, odpowiedź typu 'rozumiem'/'ok'/'sprawdzę'.\n");
        sb.append("escalate=true gdy pytanie wymaga głębokiej analizy prawnej, finansowej lub porównania wielu dokumentów — wtedy NIE odpowiadaj, zwróć TYLKO {\"response\": \"\", \"valuable\": false, \"escalate\": true, \"category\": null, \"style_observation\": null}.\n");
        sb.append("category (gdy valuable=true): TECHNICZNA | FINANSOWA | PODWYKONAWCY | MATERIALY | null.\n");
        sb.append("style_observation: jedno zdanie o stylu komunikacji usera w tej wiadomości (krótko/długo, formalnie/nieformalnie, szczegółowo/ogólnie). null gdy brak wystarczających danych.\n");

        return sb.toString();
    }

    // Warstwy 2+3 — dynamiczne RAG: wiedza firmowa+projektowa + chunki dokumentów, oba po pytaniu usera
    private String buildDynamicPrompt(Project project, String userRole, String userMessage) {
        StringBuilder sb = new StringBuilder();
        try {
            float[] queryVec = embeddingService.embed(userMessage);
            String queryVecStr = embeddingService.toVectorString(queryVec);

            // Warstwa 2 — wiedza firmowa + projektowa dopasowana do pytania
            List<String> visibleRoles = rolesUpTo(userRole);
            List<KnowledgeEntry> knowledge = knowledgeEntryRepository.findSimilarForProject(
                    project.getId(), queryVecStr, visibleRoles, RAG_KNOWLEDGE);
            log.info("PROMPT LAYER 2: knowledge entries found={}", knowledge.size());
            if (!knowledge.isEmpty()) {
                sb.append("=== WIEDZA ===\n");
                knowledge.forEach(k -> sb.append("- ").append(k.getContent()).append("\n"));
                sb.append("\n");
            }

            // Warstwa 3 — fragmenty dokumentów dopasowane do pytania
            List<DocumentChunk> chunks = chunkRepository.findSimilar(project.getId(), userRole, queryVecStr, RAG_CHUNKS);
            log.info("PROMPT LAYER 3: document chunks found={}", chunks.size());
            if (!chunks.isEmpty()) {
                sb.append("=== FRAGMENTY DOKUMENTÓW ===\n");
                chunks.forEach(c -> sb.append(c.getContent()).append("\n---\n"));
            }
        } catch (Exception e) {
            log.warn("RAG failed: {}", e.getMessage());
        }
        return sb.toString();
    }

    private String buildHistory(Conversation conversation) {
        List<Message> last = messageRepository.findLastN(conversation.getId(), HISTORY_LIMIT);
        if (last.isEmpty()) return "";

        // findLastN zwraca DESC, odwracamy na ASC
        // Aktualne pytanie usera nie jest jeszcze w DB — nic nie pomijamy
        return last.reversed().stream()
                .map(m -> m.getRole().toUpperCase() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String resolveProjectRole(User user, Project project) {
        return project.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(user.getId()))
                .map(m -> m.getRole().name())
                .findFirst()
                .orElse(user.getCompanyRole());
    }

    private List<String> rolesUpTo(String roleName) {
        try {
            Role role = Role.valueOf(roleName);
            return List.of(Role.values()).stream()
                    .filter(r -> r.isAtLeast(role) || role.isAtLeast(r))
                    .map(Enum::name)
                    .toList();
        } catch (Exception e) {
            return List.of(roleName);
        }
    }

    private Conversation createConversation(Project project, User user) {
        Conversation c = new Conversation();
        c.setProject(project);
        c.setUser(user);
        Conversation saved = conversationRepository.save(c);
        log.info("DB INSERT conversations id={} projectId={} userId={}", saved.getId(), project.getId(), user.getId());
        return saved;
    }

    private record ParsedChatResponse(String response, boolean valuable, boolean escalate, String category, String styleObservation) {}

    private ParsedChatResponse parseResponse(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
                String response = node.path("response").asText(raw);
                boolean valuable = node.path("valuable").asBoolean(false);
                boolean escalate = node.path("escalate").asBoolean(false);
                String category = node.path("category").isNull() ? null : node.path("category").asText(null);
                String styleObs = node.path("style_observation").isNull() ? null : node.path("style_observation").asText(null);
                return new ParsedChatResponse(response, valuable, escalate, category, styleObs);
            }
        } catch (Exception e) {
            log.warn("Failed to parse structured response, treating as plain text: {}", e.getMessage());
        }
        return new ParsedChatResponse(raw, false, false, null, null);
    }

    private void saveStyleObservation(User user, String observation) {
        UserStyleObservation obs = new UserStyleObservation();
        obs.setUser(user);
        obs.setObservation(observation);
        styleObservationRepository.save(obs);
        log.info("DB INSERT user_style_observations userId={} observation='{}'", user.getId(), observation);
    }

    private void saveMessage(Conversation conversation, String role, String content,
                             boolean valuable, int inputTokens, int outputTokens, String model, String category) {
        Message m = new Message();
        m.setConversation(conversation);
        m.setRole(role);
        m.setContent(content);
        m.setProcessedForKnowledge(!valuable);
        m.setInputTokens(inputTokens);
        m.setOutputTokens(outputTokens);
        m.setModel(model);
        m.setKnowledgeCategory(category);
        if (model != null && (inputTokens > 0 || outputTokens > 0)) {
            m.setCostUsd(TokenCostCalculator.calculate(model, inputTokens, outputTokens, 0, 0));
        }
        Message saved = messageRepository.save(m);
        log.info("DB INSERT messages id={} conversationId={} role={} valuable={} model={} in={} out={} cost=${}",
                saved.getId(), conversation.getId(), role, valuable, model, inputTokens, outputTokens, saved.getCostUsd());
        log.debug("DB INSERT messages content:\n{}", content);
    }
}
