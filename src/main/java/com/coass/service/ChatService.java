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
    private final ProjectMemoryRepository projectMemoryRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.model-chat:claude-haiku-4-5-20251001}")
    private String chatModel;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String escalateModel;

    private static final int HISTORY_LIMIT = 10;
    private static final int RAG_CHUNKS = 5;
    private static final int RAG_KNOWLEDGE = 3;

    @Transactional
    public String chat(Long projectId, Long userId, String userMessage) {
        User user = userRepository.findById(userId).orElseThrow();
        Project project = projectRepository.findById(projectId).orElseThrow();
        String userRole = resolveProjectRole(user, project);

        Conversation conversation = conversationRepository
                .findFirstByProjectIdAndUserIdOrderByCreatedAtDesc(projectId, userId)
                .orElseGet(() -> createConversation(project, user));

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

        saveMessage(conversation, "user", userMessage, parsed.valuable(), 0, 0, null);
        saveMessage(conversation, "assistant", parsed.response(), parsed.valuable(),
                finalResp.inputTokens(), finalResp.outputTokens(), usedModel);
        if (!parsed.valuable()) {
            log.info("Messages saved but marked processedForKnowledge=true (not valuable for knowledge extraction)");
        }

        return parsed.response();
    }

    // Warstwy 1-3 — statyczne, keszowane przez Anthropic przez 5 min
    private String buildCacheablePrompt(Project project, User user, String userRole) {
        StringBuilder sb = new StringBuilder();

        // Warstwa 1 — rola i kontekst
        sb.append("Jesteś asystentem budowlanym projektu \"").append(project.getName()).append("\".\n");
        sb.append("Rozmawiasz z użytkownikiem o roli: ").append(userRole).append(", imię: ").append(user.getName()).append(".\n");
        sb.append("Odpowiadasz po polsku, konkretnie i na temat projektu.\n\n");
        sb.append("WAŻNE: Odpowiedź zwróć WYŁĄCZNIE jako JSON bez markdown:\n");
        sb.append("{\"response\": \"twoja odpowiedź\", \"valuable\": true/false, \"escalate\": false}\n");
        sb.append("valuable=true gdy Twoja odpowiedź zawiera: ceny, kwoty, daty, nazwy firm/podwykonawców, decyzje, ustalenia, problemy techniczne, dane z dokumentów.\n");
        sb.append("valuable=false TYLKO gdy odpowiedź to: powitanie, pożegnanie, podziękowanie, prośba o doprecyzowanie bez żadnych danych.\n");
        sb.append("escalate=true TYLKO gdy pytanie wymaga głębokiej analizy prawnej, finansowej lub porównania wielu dokumentów jednocześnie. W przeciwnym razie escalate=false.\n\n");

        // Warstwa 2 — pamięć projektu
        projectMemoryRepository.findByProjectIdAndRole(project.getId(), userRole)
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .ifPresent(m -> {
                    log.info("PROMPT LAYER 2: project memory found for role={}", userRole);
                    sb.append("=== PAMIĘĆ PROJEKTU ===\n").append(m.getContent()).append("\n\n");
                });

        // Warstwa 3 — wiedza firmowa
        try {
            List<String> visibleRoles = rolesUpTo(userRole);
            // Embed neutral query — wiedza firmowa jest statyczna więc używamy uproszczonego zapytania
            float[] queryVec = embeddingService.embed("projekt budowlany " + project.getName());
            String queryVecStr = embeddingService.toVectorString(queryVec);
            List<KnowledgeEntry> knowledge = knowledgeEntryRepository.findSimilar(queryVecStr, visibleRoles, RAG_KNOWLEDGE);
            log.info("PROMPT LAYER 3: knowledge entries found={}", knowledge.size());
            if (!knowledge.isEmpty()) {
                sb.append("=== WIEDZA FIRMOWA ===\n");
                knowledge.forEach(k -> sb.append("- ").append(k.getContent()).append("\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("Knowledge RAG failed: {}", e.getMessage());
        }

        return sb.toString();
    }

    // Warstwa 4 — dynamiczne RAG chunks dopasowane do pytania usera
    private String buildDynamicPrompt(Project project, String userRole, String userMessage) {
        StringBuilder sb = new StringBuilder();
        try {
            float[] queryVec = embeddingService.embed(userMessage);
            String queryVecStr = embeddingService.toVectorString(queryVec);
            List<DocumentChunk> chunks = chunkRepository.findSimilar(project.getId(), userRole, queryVecStr, RAG_CHUNKS);
            log.info("PROMPT LAYER 4: document chunks found={}", chunks.size());
            if (!chunks.isEmpty()) {
                sb.append("=== FRAGMENTY DOKUMENTÓW ===\n");
                chunks.forEach(c -> sb.append(c.getContent()).append("\n---\n"));
            }
        } catch (Exception e) {
            log.warn("Document RAG failed: {}", e.getMessage());
        }
        return sb.toString();
    }

    private String buildHistory(Conversation conversation) {
        List<Message> last = messageRepository.findLastN(conversation.getId(), HISTORY_LIMIT);
        if (last.isEmpty()) return "";

        // findLastN zwraca DESC, odwracamy
        List<Message> ordered = last.reversed();
        // Pomijamy ostatnią wiadomość usera - jest już w userMessage
        List<Message> history = ordered.size() > 1
                ? ordered.subList(0, ordered.size() - 1)
                : List.of();

        return history.stream()
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

    private record ParsedChatResponse(String response, boolean valuable, boolean escalate) {}

    private ParsedChatResponse parseResponse(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
                String response = node.path("response").asText(raw);
                boolean valuable = node.path("valuable").asBoolean(false);
                boolean escalate = node.path("escalate").asBoolean(false);
                return new ParsedChatResponse(response, valuable, escalate);
            }
        } catch (Exception e) {
            log.warn("Failed to parse structured response, treating as plain text: {}", e.getMessage());
        }
        return new ParsedChatResponse(raw, false, false);
    }

    private void saveMessage(Conversation conversation, String role, String content,
                             boolean valuable, int inputTokens, int outputTokens, String model) {
        Message m = new Message();
        m.setConversation(conversation);
        m.setRole(role);
        m.setContent(content);
        m.setProcessedForKnowledge(!valuable);
        m.setInputTokens(inputTokens);
        m.setOutputTokens(outputTokens);
        m.setModel(model);
        if (model != null && (inputTokens > 0 || outputTokens > 0)) {
            m.setCostUsd(TokenCostCalculator.calculate(model, inputTokens, outputTokens, 0, 0));
        }
        Message saved = messageRepository.save(m);
        log.info("DB INSERT messages id={} conversationId={} role={} valuable={} model={} in={} out={} cost=${}",
                saved.getId(), conversation.getId(), role, valuable, model, inputTokens, outputTokens, saved.getCostUsd());
        log.debug("DB INSERT messages content:\n{}", content);
    }
}
