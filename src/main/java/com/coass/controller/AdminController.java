package com.coass.controller;

import com.coass.entity.KnowledgeEntry;
import com.coass.entity.UserStyleObservation;
import com.coass.repository.DocumentChunkRepository;
import com.coass.repository.EmbeddingUsageRepository;
import com.coass.repository.KnowledgeEntryRepository;
import com.coass.repository.MessageRepository;
import com.coass.repository.UserRepository;
import com.coass.repository.UserStyleObservationRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.NightlyAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final NightlyAgentService nightlyAgentService;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final UserStyleObservationRepository styleObservationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingUsageRepository embeddingUsageRepository;

    // =========================================================
    // Triggery nightly agenta
    // =========================================================

    @PostMapping("/nightly/run")
    public ResponseEntity<Map<String, Object>> runNightly() {
        log.info("Manual nightly agent trigger via API");
        CompletableFuture.runAsync(nightlyAgentService::runNightly);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "check", "GET /api/admin/knowledge"));
    }

    @PostMapping("/nightly/knowledge")
    public ResponseEntity<Map<String, Object>> runKnowledge() {
        CompletableFuture.runAsync(nightlyAgentService::processKnowledge);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "check", "GET /api/admin/knowledge"));
    }

    // Sync — sekwencyjny chat(), wyniki od razu (droższe, dla admina/testów)
    @PostMapping("/nightly/knowledge/sync")
    public ResponseEntity<Map<String, Object>> runKnowledgeSync() {
        long start = System.currentTimeMillis();
        nightlyAgentService.processKnowledgeSync();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "durationMs", System.currentTimeMillis() - start,
                "check", "GET /api/admin/knowledge"
        ));
    }

    @PostMapping("/nightly/styles")
    public ResponseEntity<Map<String, Object>> runStyles() {
        CompletableFuture.runAsync(nightlyAgentService::consolidateUserStylesBatch);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "check", "GET /api/admin/styles"));
    }

    @PostMapping("/nightly/styles/sync")
    public ResponseEntity<Map<String, Object>> runStylesSync() {
        long start = System.currentTimeMillis();
        nightlyAgentService.consolidateUserStylesSync();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "durationMs", System.currentTimeMillis() - start,
                "check", "GET /api/admin/styles"
        ));
    }

    @PostMapping("/nightly/cleanup")
    public ResponseEntity<Map<String, Object>> runCleanup() {
        CompletableFuture.runAsync(nightlyAgentService::cleanupExpiredKnowledge);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    @PostMapping("/weekly/consolidate")
    public ResponseEntity<Map<String, Object>> runWeekly() {
        CompletableFuture.runAsync(nightlyAgentService::consolidateDuplicateKnowledge);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "check", "GET /api/admin/knowledge"));
    }

    // Resetuje processedForKnowledge=false dla wszystkich wiadomości (np. po zmianie prompta)
    @PostMapping("/messages/reprocess")
    public ResponseEntity<Map<String, Object>> reprocessMessages(
            @RequestParam(defaultValue = "90") int days) {
        var messages = messageRepository.findAll().stream()
                .filter(m -> m.getCreatedAt().isAfter(LocalDateTime.now().minusDays(days)))
                .toList();
        messages.forEach(m -> m.setProcessedForKnowledge(false));
        messageRepository.saveAll(messages);
        log.info("REPROCESS reset processedForKnowledge for {} messages (last {} days)", messages.size(), days);
        return ResponseEntity.ok(Map.of("reset", messages.size(), "days", days));
    }

    // =========================================================
    // Podgląd wiedzy
    // =========================================================

    @GetMapping("/knowledge")
    public ResponseEntity<List<Map<String, Object>>> getAllKnowledge() {
        return ResponseEntity.ok(knowledgeEntryRepository.findAll().stream()
                .map(this::toKnowledgeMap)
                .toList());
    }

    @GetMapping("/knowledge/project/{projectId}")
    public ResponseEntity<List<Map<String, Object>>> getProjectKnowledge(@PathVariable Long projectId) {
        return ResponseEntity.ok(knowledgeEntryRepository.findByProjectId(projectId).stream()
                .map(this::toKnowledgeMap)
                .toList());
    }

    @GetMapping("/knowledge/company")
    public ResponseEntity<List<Map<String, Object>>> getCompanyKnowledge() {
        return ResponseEntity.ok(knowledgeEntryRepository.findAll().stream()
                .filter(k -> k.getProject() == null)
                .map(this::toKnowledgeMap)
                .toList());
    }

    @PatchMapping("/knowledge/{id}")
    public ResponseEntity<Map<String, Object>> updateKnowledge(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        KnowledgeEntry entry = knowledgeEntryRepository.findById(id).orElse(null);
        if (entry == null) return ResponseEntity.notFound().build();
        if (body.containsKey("content")) {
            entry.setContent((String) body.get("content"));
            entry.setEmbedding(null); // reset — zostanie odtworzone przez nightly
        }
        if (body.containsKey("category")) entry.setCategory((String) body.get("category"));
        if (body.containsKey("confidence")) {
            Object c = body.get("confidence");
            entry.setConfidence(c instanceof Number n ? n.intValue() : Integer.parseInt(c.toString()));
        }
        entry.setLastConfirmedAt(java.time.LocalDateTime.now());
        knowledgeEntryRepository.save(entry);
        return ResponseEntity.ok(toKnowledgeMap(entry));
    }

    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<Void> deleteKnowledge(@PathVariable Long id) {
        if (!knowledgeEntryRepository.existsById(id)) return ResponseEntity.notFound().build();
        knowledgeEntryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================
    // Użytkownicy
    // =========================================================

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getUsers(@AuthenticationPrincipal CoassUserDetails principal) {
        String role = principal.getCompanyRole();
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userRepository.findAll().stream()
                .map(u -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    m.put("companyRole", u.getCompanyRole());
                    m.put("formalityLevel", u.getFormalityLevel());
                    return m;
                })
                .toList());
    }

    // =========================================================
    // Podgląd stylu użytkowników
    // =========================================================

    @GetMapping("/styles")
    public ResponseEntity<List<Map<String, Object>>> getStyleObservations() {
        return ResponseEntity.ok(styleObservationRepository.findAll().stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.getId(),
                        "userId", o.getUser().getId(),
                        "userName", o.getUser().getName(),
                        "observation", o.getObservation(),
                        "createdAt", o.getCreatedAt().toString()
                ))
                .toList());
    }

    // =========================================================
    // Podgląd nieprzetworzonch wiadomości
    // =========================================================

    @GetMapping("/messages/unprocessed")
    public ResponseEntity<List<Map<String, Object>>> getUnprocessed() {
        return ResponseEntity.ok(
                messageRepository.findUnprocessedSince(LocalDateTime.now().minusDays(30)).stream()
                        .map(m -> Map.<String, Object>of(
                                "id", m.getId(),
                                "role", m.getRole(),
                                "content", m.getContent().substring(0, Math.min(200, m.getContent().length())),
                                "category", m.getKnowledgeCategory() != null ? m.getKnowledgeCategory() : "",
                                "createdAt", m.getCreatedAt().toString()
                        ))
                        .toList()
        );
    }

    // =========================================================
    // Token stats
    // =========================================================

    @GetMapping("/tokens/stats")
    public ResponseEntity<Map<String, Object>> getTokenStats() {
        List<Object[]> tokenStatsList = messageRepository.getTokenStats();
        Object[] row = tokenStatsList.isEmpty() ? new Object[10] : tokenStatsList.get(0);

        Map<String, Object> totals = new java.util.LinkedHashMap<>();
        totals.put("messages",      toLong(row[0]));
        totals.put("inputTokens",   toLong(row[1]));
        totals.put("outputTokens",  toLong(row[2]));
        totals.put("costUsd",       row[3]);

        Map<String, Object> today = new java.util.LinkedHashMap<>();
        today.put("inputTokens",  toLong(row[4]));
        today.put("outputTokens", toLong(row[5]));
        today.put("costUsd",      row[6]);

        Map<String, Object> month = new java.util.LinkedHashMap<>();
        month.put("inputTokens",  toLong(row[7]));
        month.put("outputTokens", toLong(row[8]));
        month.put("costUsd",      row[9]);

        List<Map<String, Object>> byModel = messageRepository.getStatsByModel().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("model",        r[0]);
                    m.put("messages",     toLong(r[1]));
                    m.put("inputTokens",  toLong(r[2]));
                    m.put("outputTokens", toLong(r[3]));
                    m.put("costUsd",      r[4]);
                    return m;
                }).toList();

        List<Map<String, Object>> byProject = messageRepository.getStatsByProject().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("projectId",    toLong(r[0]));
                    m.put("projectName",  r[1]);
                    m.put("conversations",toLong(r[2]));
                    m.put("activeUsers",  toLong(r[3]));
                    m.put("messages",     toLong(r[4]));
                    m.put("inputTokens",  toLong(r[5]));
                    m.put("outputTokens", toLong(r[6]));
                    m.put("costUsd",      r[7]);
                    return m;
                }).toList();

        List<Map<String, Object>> byConversation = messageRepository.getStatsByConversation().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("conversationId",  toLong(r[0]));
                    m.put("title",           r[1] != null ? r[1] : "Nowa rozmowa");
                    m.put("projectName",     r[2]);
                    m.put("userName",        r[3]);
                    m.put("messages",        toLong(r[4]));
                    m.put("inputTokens",     toLong(r[5]));
                    m.put("outputTokens",    toLong(r[6]));
                    m.put("costUsd",         r[7]);
                    m.put("startedAt",       r[8] != null ? r[8].toString() : null);
                    m.put("lastMessageAt",   r[9] != null ? r[9].toString() : null);
                    return m;
                }).toList();

        List<Map<String, Object>> daily = messageRepository.getDailyStats().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("day",          r[0].toString());
                    m.put("inputTokens",  toLong(r[1]));
                    m.put("outputTokens", toLong(r[2]));
                    m.put("costUsd",      r[3]);
                    return m;
                }).toList();

        List<Map<String, Object>> docsByProject = chunkRepository.getDocumentStatsByProject().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("projectId",     toLong(r[0]));
                    m.put("projectName",   r[1]);
                    m.put("documents",     toLong(r[2]));
                    m.put("chunks",        toLong(r[3]));
                    m.put("indexedChunks", toLong(r[4]));
                    return m;
                }).toList();

        List<Map<String, Object>> dailyDocs = chunkRepository.getDailyDocumentStats().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("day",       r[0].toString());
                    m.put("documents", toLong(r[1]));
                    m.put("chunks",    toLong(r[2]));
                    return m;
                }).toList();

        List<Map<String, Object>> embeddingBySource = embeddingUsageRepository.getStatsBySource().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("source",      r[0]);
                    m.put("calls",       toLong(r[1]));
                    m.put("totalChars",  toLong(r[2]));
                    m.put("todayChars",  toLong(r[3]));
                    m.put("monthChars",  toLong(r[4]));
                    return m;
                }).toList();

        List<Map<String, Object>> embeddingByProject = embeddingUsageRepository.getStatsByProject().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("projectId",   toLong(r[0]));
                    m.put("projectName", r[1]);
                    m.put("calls",       toLong(r[2]));
                    m.put("totalChars",  toLong(r[3]));
                    return m;
                }).toList();

        List<Map<String, Object>> embeddingDaily = embeddingUsageRepository.getDailyStats().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("day",        r[0].toString());
                    m.put("calls",      toLong(r[1]));
                    m.put("totalChars", toLong(r[2]));
                    return m;
                }).toList();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totals",              totals);
        result.put("today",               today);
        result.put("thisMonth",           month);
        result.put("byModel",             byModel);
        result.put("byProject",           byProject);
        result.put("byConversation",      byConversation);
        result.put("daily",               daily);
        result.put("filesByProject",      docsByProject);
        result.put("filesDaily",          dailyDocs);
        result.put("embeddingBySource",   embeddingBySource);
        result.put("embeddingByProject",  embeddingByProject);
        result.put("embeddingDaily",      embeddingDaily);
        return ResponseEntity.ok(result);
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    // =========================================================
    // Helper
    // =========================================================

    private Map<String, Object> toKnowledgeMap(KnowledgeEntry k) {
        return Map.of(
                "id", k.getId(),
                "content", k.getContent(),
                "category", k.getCategory() != null ? k.getCategory() : "",
                "entryType", k.getEntryType(),
                "sourceRole", k.getSourceRole() != null ? k.getSourceRole() : "",
                "projectId", k.getProject() != null ? k.getProject().getId() : "null",
                "confidence", k.getConfidence(),
                "validUntil", k.getValidUntil() != null ? k.getValidUntil().toString() : "",
                "createdAt", k.getCreatedAt().toString()
        );
    }
}
