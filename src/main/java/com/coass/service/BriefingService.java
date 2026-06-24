package com.coass.service;

import com.coass.entity.KnowledgeEntry;
import com.coass.entity.ProjectBriefing;
import com.coass.entity.Role;
import com.coass.repository.KnowledgeEntryRepository;
import com.coass.repository.ProjectBriefingRepository;
import com.coass.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BriefingService {

    private final AiChatService aiChatService;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final ProjectBriefingRepository briefingRepository;

    @Value("${anthropic.model-chat:claude-haiku-4-5-20251001}")
    private String chatModel;

    private static final int CACHE_HOURS = 1;

    @Transactional
    public Map<String, Object> getBriefing(Long projectId, Long userId, boolean forceRefresh) {
        Role role = projectService.requireMembership(projectId, userId);
        String roleName = role.name();

        if (!forceRefresh) {
            var cached = briefingRepository.findByProjectIdAndRoleName(projectId, roleName)
                    .filter(b -> b.getGeneratedAt().isAfter(LocalDateTime.now().minusHours(CACHE_HOURS)));
            if (cached.isPresent()) {
                ProjectBriefing b = cached.get();
                log.debug("Briefing cache hit projectId={} role={}", projectId, roleName);
                return Map.of("content", b.getContent(), "generatedAt", b.getGeneratedAt().toString(), "cached", true);
            }
        }

        String content = generate(projectId, role);

        ProjectBriefing briefing = briefingRepository.findByProjectIdAndRoleName(projectId, roleName)
                .orElse(new ProjectBriefing());
        briefing.setProjectId(projectId);
        briefing.setRoleName(roleName);
        briefing.setContent(content);
        briefing.setGeneratedAt(LocalDateTime.now());
        briefingRepository.save(briefing);

        log.info("Briefing generated projectId={} role={} chars={}", projectId, roleName, content.length());
        return Map.of("content", content, "generatedAt", briefing.getGeneratedAt().toString(), "cached", false);
    }

    private String generate(Long projectId, Role role) {
        String projectName = projectRepository.findById(projectId)
                .map(p -> p.getName()).orElse("projekt");

        List<String> visibleRoles = rolesUpTo(role);
        List<KnowledgeEntry> entries = knowledgeEntryRepository.findRecentForBriefing(projectId, visibleRoles);

        if (entries.isEmpty()) {
            return "Brak wystarczających danych do wygenerowania podsumowania — prowadź rozmowy z asystentem, aby zbudować bazę wiedzy projektu.";
        }

        StringBuilder ctx = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            ctx.append("- [").append(e.getCategory() != null ? e.getCategory() : "OGÓLNE").append("] ")
               .append(e.getContent()).append("\n");
        }

        String systemPrompt = "Jesteś asystentem budowlanym. Odpowiadasz wyłącznie po polsku. Bądź konkretny i zwięzły.";
        String userPrompt = String.format("""
                Projekt: %s
                Rola użytkownika: %s

                Poniżej znajdują się ostatnie informacje z projektu. Mogą zawierać dane z różnych obszarów.
                Wybierz i uwzględnij tylko to, co jest istotne dla roli %s — pomiń rzeczy nieistotne dla tej roli.

                %s

                Napisz zwięzłe podsumowanie (3–5 zdań) aktualnej sytuacji na projekcie z perspektywy roli %s.
                Zacznij od razu od treści — bez wstępu ani nagłówka.
                """,
                projectName, role.name(), role.name(), ctx, role.name());

        ChatResponse resp = aiChatService.chat(
                ChatRequest.of(systemPrompt, userPrompt).withModelOverride(chatModel));
        return resp.text().strip();
    }

    private List<String> rolesUpTo(Role role) {
        return Arrays.stream(Role.values())
                .filter(r -> role.isAtLeast(r))
                .map(Enum::name)
                .toList();
    }
}
