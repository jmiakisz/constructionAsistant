package com.coass.controller;

import com.coass.entity.KnowledgeEntry;
import com.coass.entity.UserStyleObservation;
import com.coass.repository.KnowledgeEntryRepository;
import com.coass.repository.MessageRepository;
import com.coass.repository.UserStyleObservationRepository;
import com.coass.service.NightlyAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
