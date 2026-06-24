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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final DocumentRepository documentRepository;
    private final ProjectNotificationService notificationService;
    private final RoleConfigService roleConfigService;

    @Value("${anthropic.model-chat:claude-haiku-4-5-20251001}")
    private String chatModel;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String escalateModel;

    private static final int HISTORY_LIMIT = 10;
    private static final int RAG_CHUNKS = 5;
    private static final int RAG_KNOWLEDGE = 5;
    private static final double RAG_MAX_DISTANCE = 0.45;
    private static final int COMPACT_THRESHOLD = 50;
    private static final int COMPACT_KEEP = 10;

    @Transactional
    public Conversation createConversation(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        return createConversation(project, user);
    }

    @Transactional
    public ChatResult chat(Long projectId, Long userId, Long conversationId, String userMessage,
                           List<AttachedFile> attachments) {
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

        // Rozwiąż referencje [Dokument: nazwa.pdf] → rzeczywiste załączniki z dysku
        List<AttachedFile> resolvedDocs = extractDocumentRefs(userMessage, project);
        final List<AttachedFile> uploadedAttachments = attachments; // tylko przesłane pliki, do adnotacji
        if (!resolvedDocs.isEmpty()) {
            List<AttachedFile> merged = new ArrayList<>(attachments);
            merged.addAll(resolvedDocs);
            attachments = merged;
        }

        String cacheablePrompt = buildCacheablePrompt(project, user, userRole);
        String dynamicPrompt = buildDynamicPrompt(project, userRole, userMessage);
        List<HistoryTurn> history = buildHistory(conversation);

        log.debug("CACHEABLE SYSTEM PROMPT:\n{}", cacheablePrompt);
        log.debug("DYNAMIC SYSTEM PROMPT:\n{}", dynamicPrompt);
        log.debug("HISTORY turns={}", history.size());

        // #3 — Haiku odpowiada, jeśli escalate=true → Sonnet
        ChatResponse haikusResp = aiChatService.chat(
                ChatRequest.withCache(cacheablePrompt, dynamicPrompt, userMessage, attachments, history)
                        .withModelOverride(chatModel));
        log.debug("HAIKU RAW RESPONSE:\n{}", haikusResp.text());

        ParsedChatResponse parsed = parseResponse(haikusResp.text());
        ChatResponse finalResp = haikusResp;
        String usedModel = chatModel;

        if (parsed.escalate()) {
            log.info("=== ESCALATING TO SONNET === projectId={} userId={}", projectId, userId);
            finalResp = aiChatService.chat(
                    ChatRequest.withCache(cacheablePrompt, dynamicPrompt, userMessage, attachments, history)
                            .withModelOverride(escalateModel));
            parsed = parseResponse(finalResp.text());
            usedModel = escalateModel;
            log.debug("SONNET RAW RESPONSE:\n{}", finalResp.text());
        }

        log.info("=== CHAT RESPONSE === projectId={} userId={} model={} valuable={} escalated={} in={} out={}",
                projectId, userId, usedModel, parsed.valuable(), !usedModel.equals(chatModel),
                finalResp.inputTokens(), finalResp.outputTokens());

        String savedUserMessage = userMessage;
        if (!uploadedAttachments.isEmpty()) {
            String fileList = uploadedAttachments.stream().map(AttachedFile::fileName).collect(Collectors.joining(", "));
            savedUserMessage = userMessage + "\n[Załączniki: " + fileList + "]";
        }
        saveMessage(conversation, "user", savedUserMessage, parsed.valuable(), 0, 0, null, parsed.category());
        saveMessage(conversation, "assistant", parsed.response(), parsed.valuable(),
                finalResp.inputTokens(), finalResp.outputTokens(), usedModel, parsed.category());

        if (parsed.notifyAdmin() && parsed.adminQuestion() != null && !parsed.adminQuestion().isBlank()) {
            notificationService.create(projectId, userId, conversationId, parsed.adminQuestion());
            log.info("=== ADMIN NOTIFICATION === projectId={} userId={} question='{}'",
                    projectId, userId, parsed.adminQuestion());
        }

        if (parsed.styleObservation() != null) {
            saveStyleObservation(user, parsed.styleObservation());
        }
        if (!parsed.valuable()) {
            log.info("Messages saved but marked processedForKnowledge=true (not valuable for knowledge extraction)");
        }

        maybeCompact(conversation);

        if (parsed.document() != null) {
            log.info("=== DOCUMENT GENERATED === title='{}' chars={}", parsed.document().title(), parsed.document().content().length());
        }

        return new ChatResult(parsed.response(), parsed.document());
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
        sb.append("DOKUMENTY:\n");
        sb.append("Gdy użytkownik prosi o wygenerowanie dokumentu (oferta, raport, protokół, zestawienie, umowa, notatka, pismo, harmonogram, plan itp.) — wypełnij pole 'document' z tytułem i treścią w formacie Markdown.\n");
        sb.append("W polu 'response' napisz krótkie potwierdzenie co wygenerowałeś (1-2 zdania). Użytkownik sam pobierze dokument.\n");
        sb.append("Nie możesz wysyłać dokumentów e-mailem ani uploadować ich — generujesz tylko treść.\n");
        sb.append("Gdy NIE generujesz dokumentu (normalna rozmowa) — 'document' musi być null.\n\n");
        sb.append("WAŻNE: Odpowiedź zwróć WYŁĄCZNIE jako JSON bez markdown:\n");
        sb.append("{\"response\": \"twoja odpowiedź\", \"valuable\": true/false, \"escalate\": false, \"category\": null, \"style_observation\": null, \"notify_admin\": false, \"admin_question\": null, \"document\": null}\n");
        sb.append("document: null lub {\"title\": \"Tytuł dokumentu\", \"content\": \"# Treść w Markdown...\"} gdy generujesz dokument.\n");
        sb.append("valuable=true gdy KTÓRAKOLWIEK strona podaje konkretny fakt: cena, kwota, data, termin, nazwa firmy/podwykonawcy/materiału, decyzja, ustalenie, postęp robót (co zostało zrobione/odebrane/dostarczone), problem techniczny, ryzyko, dane z dokumentów, kto co zrobił/powie/sprawdzi.\n");
        sb.append("valuable=false TYLKO gdy: samo powitanie/pożegnanie/podziękowanie, prośba o doprecyzowanie BEZ podania faktów, odpowiedź 'rozumiem'/'ok' BEZ żadnych konkretnych informacji.\n");
        sb.append("Wątpliwość → valuable=true. Lepiej zapisać za dużo niż za mało.\n");
        sb.append("escalate=true gdy pytanie wymaga głębokiej analizy prawnej, finansowej lub porównania wielu dokumentów — wtedy NIE odpowiadaj, zwróć TYLKO {\"response\": \"\", \"valuable\": false, \"escalate\": true, \"category\": null, \"style_observation\": null, \"notify_admin\": false, \"admin_question\": null}.\n");
        sb.append("category (gdy valuable=true): TECHNICZNA | FINANSOWA | PODWYKONAWCY | MATERIALY | null.\n");
        sb.append("style_observation: jedno zdanie o stylu komunikacji usera w tej wiadomości. null gdy brak danych.\n");
        sb.append("notify_admin=true TYLKO gdy user JAWNIE prosi o przekazanie pytania/informacji do zarządzającego/admina/kierownika/managementu. Nie ustawiaj gdy user sam pyta asystenta.\n");
        sb.append("admin_question (gdy notify_admin=true): zwięzła treść pytania/informacji do przekazania zarządzającemu, w imieniu użytkownika.\n");

        return sb.toString();
    }

    // Warstwy 2+3 — dynamiczne RAG: wiedza firmowa+projektowa + chunki dokumentów, oba po pytaniu usera
    private String buildDynamicPrompt(Project project, String userRole, String userMessage) {
        StringBuilder sb = new StringBuilder();
        try {
            float[] queryVec = embeddingService.embed(userMessage, "CHAT_QUERY", project);
            String queryVecStr = embeddingService.toVectorString(queryVec);

            // Warstwa 2 — wiedza firmowa + projektowa dopasowana do pytania
            List<String> visibleRoles = rolesUpTo(userRole);
            List<KnowledgeEntry> knowledge = knowledgeEntryRepository.findSimilarForProject(
                    project.getId(), queryVecStr, visibleRoles, RAG_KNOWLEDGE, RAG_MAX_DISTANCE);
            log.info("PROMPT LAYER 2: knowledge entries found={} (threshold<={})", knowledge.size(), RAG_MAX_DISTANCE);
            if (!knowledge.isEmpty()) {
                knowledge.forEach(k -> log.info("  [KNOWLEDGE] [{}] {}", k.getCategory(), k.getContent()));
                sb.append("=== WIEDZA ===\n");
                knowledge.forEach(k -> sb.append("- ").append(k.getContent()).append("\n"));
                sb.append("\n");
            }

            // Warstwa 3 — fragmenty dokumentów dopasowane do pytania
            List<DocumentChunk> chunks = chunkRepository.findSimilar(project.getId(), userRole, queryVecStr, RAG_CHUNKS, RAG_MAX_DISTANCE);
            log.info("PROMPT LAYER 3: document chunks found={}", chunks.size());
            if (!chunks.isEmpty()) {
                chunks.forEach(c -> log.info("  [CHUNK] doc={} content={}", c.getDocument() != null ? c.getDocument().getName() : "?", c.getContent().substring(0, Math.min(120, c.getContent().length()))));
                sb.append("=== FRAGMENTY DOKUMENTÓW ===\n");
                chunks.forEach(c -> sb.append(c.getContent()).append("\n---\n"));
            }
        } catch (Exception e) {
            log.warn("RAG failed: {}", e.getMessage());
        }
        return sb.toString();
    }

    private List<HistoryTurn> buildHistory(Conversation conversation) {
        List<Message> last = messageRepository.findLastN(conversation.getId(), HISTORY_LIMIT);
        List<HistoryTurn> turns = new ArrayList<>();

        // Streszczenie starszych wiadomości jako pierwsza wiadomość user/assistant
        if (conversation.getSummary() != null) {
            turns.add(new HistoryTurn("user", "[Wcześniejsza rozmowa — proszę uwzględnij ten kontekst]"));
            turns.add(new HistoryTurn("assistant", "Rozumiem. Oto podsumowanie wcześniejszej rozmowy:\n" + conversation.getSummary()));
        }

        if (!last.isEmpty()) {
            // findLastN zwraca DESC, odwracamy na ASC
            last.reversed().forEach(m ->
                    turns.add(new HistoryTurn(m.getRole(), m.getContent())));
        }

        return turns;
    }

    private void maybeCompact(Conversation conversation) {
        long count = messageRepository.countByConversationId(conversation.getId());
        if (count < COMPACT_THRESHOLD) return;

        int toArchive = (int) count - COMPACT_KEEP;
        List<Message> old = messageRepository.findOldestN(conversation.getId(), toArchive);
        if (old.isEmpty()) return;

        log.info("COMPACT conversation={} archiving {} messages (total={})", conversation.getId(), old.size(), count);

        String transcript = old.stream()
                .map(m -> m.getRole().toUpperCase() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        String sysPrompt = "Zrób zwięzłe streszczenie poniższej rozmowy (max 500 słów) zachowując kluczowe fakty, decyzje, ustalenia, nazwy i kwoty. Odpowiedz TYLKO streszczeniem, bez wstępu.";

        try {
            ChatResponse summaryResp = aiChatService.chat(
                    ChatRequest.of(sysPrompt, transcript).withModelOverride(chatModel));
            String newSummary = summaryResp.text().strip();

            if (conversation.getSummary() != null) {
                newSummary = conversation.getSummary() + "\n---\n" + newSummary;
            }
            conversation.setSummary(newSummary);
            conversationRepository.save(conversation);

            messageRepository.deleteAll(old);
            log.info("COMPACT done conversation={} summary_length={}", conversation.getId(), newSummary.length());
        } catch (Exception e) {
            log.warn("COMPACT failed conversation={}: {}", conversation.getId(), e.getMessage());
        }
    }

    private String resolveProjectRole(User user, Project project) {
        return project.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(user.getId()))
                .map(ProjectMember::getRoleKey)
                .findFirst()
                .orElse(user.getCompanyRole());
    }

    private List<String> rolesUpTo(String roleKey) {
        return roleConfigService.rolesUpTo(roleKey);
    }

    private Conversation createConversation(Project project, User user) {
        Conversation c = new Conversation();
        c.setProject(project);
        c.setUser(user);
        Conversation saved = conversationRepository.save(c);
        log.info("DB INSERT conversations id={} projectId={} userId={}", saved.getId(), project.getId(), user.getId());
        return saved;
    }

    public record DocumentAttachment(String title, String content) {}
    public record ChatResult(String response, DocumentAttachment document) {}
    private record ParsedChatResponse(String response, boolean valuable, boolean escalate, String category, String styleObservation, boolean notifyAdmin, String adminQuestion, DocumentAttachment document) {}

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
                boolean notifyAdmin = node.path("notify_admin").asBoolean(false);
                String adminQuestion = node.path("admin_question").isNull() ? null : node.path("admin_question").asText(null);
                DocumentAttachment document = null;
                JsonNode docNode = node.path("document");
                if (!docNode.isMissingNode() && !docNode.isNull() && docNode.isObject()) {
                    String title = docNode.path("title").asText(null);
                    String content = docNode.path("content").asText(null);
                    if (title != null && content != null) document = new DocumentAttachment(title, content);
                }
                return new ParsedChatResponse(response, valuable, escalate, category, styleObs, notifyAdmin, adminQuestion, document);
            }
        } catch (Exception e) {
            log.warn("Failed to parse structured response, treating as plain text: {}", e.getMessage());
        }
        return new ParsedChatResponse(raw, false, false, null, null, false, null, null);
    }

    private void saveStyleObservation(User user, String observation) {
        UserStyleObservation obs = new UserStyleObservation();
        obs.setUser(user);
        obs.setObservation(observation);
        styleObservationRepository.save(obs);
        log.info("DB INSERT user_style_observations userId={} observation='{}'", user.getId(), observation);
    }

    private static final Pattern DOC_REF_PATTERN = Pattern.compile("\\[Dokument: ([^\\]]+)]");

    private List<AttachedFile> extractDocumentRefs(String message, Project project) {
        List<AttachedFile> result = new ArrayList<>();
        Matcher m = DOC_REF_PATTERN.matcher(message);
        while (m.find()) {
            String name = m.group(1).trim();
            documentRepository.findByProjectIdAndName(project.getId(), name).ifPresent(doc -> {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(doc.getFilePath()));
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    result.add(new AttachedFile(name, "application/pdf", base64));
                    log.info("  [DOC REF] resolved '{}' from {}", name, doc.getFilePath());
                } catch (Exception e) {
                    log.warn("  [DOC REF] cannot read file '{}': {}", name, e.getMessage());
                }
            });
        }
        return result;
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
