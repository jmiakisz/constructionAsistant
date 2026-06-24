package com.coass.service;

import com.coass.entity.ProjectNotification;
import com.coass.entity.Role;
import com.coass.repository.ProjectNotificationRepository;
import com.coass.repository.ProjectRepository;
import com.coass.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectNotificationService {

    private final ProjectNotificationRepository notificationRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectService projectService;

    @Transactional
    public void create(Long projectId, Long senderUserId, Long conversationId, String question) {
        ProjectNotification n = new ProjectNotification();
        n.setProject(projectRepository.getReferenceById(projectId));
        n.setSenderUser(userRepository.getReferenceById(senderUserId));
        n.setConversationId(conversationId);
        n.setQuestion(question);
        notificationRepository.save(n);
        log.info("Notification created projectId={} sender={} question='{}'", projectId, senderUserId, question);
    }

    @Transactional
    public Map<String, Object> reply(Long notificationId, Long adminUserId, String response) {
        ProjectNotification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        projectService.requireMembership(n.getProject().getId(), adminUserId);
        n.setAdminResponse(response);
        n.setAnsweredByUser(userRepository.getReferenceById(adminUserId));
        n.setStatus("ANSWERED");
        n.setAnsweredAt(LocalDateTime.now());
        notificationRepository.save(n);
        log.info("Notification answered id={} by={}", notificationId, adminUserId);
        return toMap(n);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForProject(Long projectId, Long userId) {
        Role role = projectService.requireMembership(projectId, userId);
        List<ProjectNotification> list = role.isAtLeast(Role.KIEROWNIK)
                ? notificationRepository.findAllForProject(projectId)
                : notificationRepository.findForUser(projectId, userId);
        return list.stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public long countPending(Long projectId) {
        return notificationRepository.countPendingForProject(projectId);
    }

    private Map<String, Object> toMap(ProjectNotification n) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("projectId", n.getProject().getId());
        m.put("conversationId", n.getConversationId());
        m.put("senderName", n.getSenderUser().getName());
        m.put("senderUserId", n.getSenderUser().getId());
        m.put("question", n.getQuestion());
        m.put("adminResponse", n.getAdminResponse());
        m.put("answeredBy", n.getAnsweredByUser() != null ? n.getAnsweredByUser().getName() : null);
        m.put("status", n.getStatus());
        m.put("createdAt", n.getCreatedAt().toString());
        m.put("answeredAt", n.getAnsweredAt() != null ? n.getAnsweredAt().toString() : null);
        return m;
    }
}
