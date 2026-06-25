package com.coass.service;

import com.coass.dto.project.ProjectRequest;
import com.coass.dto.project.ProjectResponse;
import com.coass.entity.*;
import com.coass.repository.ProjectMemberRepository;
import com.coass.repository.ProjectRepository;
import com.coass.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final RoleConfigService roleConfigService;

    @Transactional
    public ProjectResponse create(ProjectRequest req, Long userId) {
        User user = userRepository.getReferenceById(userId);

        Project project = new Project();
        project.setName(req.name());
        project.setDescription(req.description());
        project.setCreatedBy(user);
        projectRepository.save(project);

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRoleKey("OWNER");
        memberRepository.save(member);

        return ProjectResponse.from(project, "OWNER");
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        boolean isCompanyAdmin = "ADMIN".equals(user.getCompanyRole()) || "OWNER".equals(user.getCompanyRole());

        if (isCompanyAdmin) {
            return projectRepository.findAll().stream()
                    .map(p -> {
                        String roleKey = memberRepository.findRoleByProjectAndUser(p.getId(), userId)
                                .orElse("ADMIN");
                        return ProjectResponse.from(p, roleKey);
                    })
                    .toList();
        }

        return projectRepository.findByMemberUserId(userId).stream()
                .map(p -> {
                    String roleKey = memberRepository.findRoleByProjectAndUser(p.getId(), userId).orElse("PODWYKONAWCA");
                    return ProjectResponse.from(p, roleKey);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getForUser(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        String roleKey = memberRepository.findRoleByProjectAndUser(projectId, userId)
                .orElseThrow(() -> new AccessDeniedException("Not a member of this project"));
        return ProjectResponse.from(project, roleKey);
    }

    @Transactional
    public void addMember(Long projectId, Long requesterId, Long targetUserId, String targetRoleKey) {
        String requesterRole = memberRepository.findRoleByProjectAndUser(projectId, requesterId)
                .orElseThrow(() -> new AccessDeniedException("Not a member"));
        roleConfigService.requireAtLeast(requesterRole, "ADMIN");

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Project project = projectRepository.getReferenceById(projectId);

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(target);
        member.setRoleKey(targetRoleKey);
        memberRepository.save(member);
    }

    public String requireMembership(Long projectId, Long userId) {
        java.util.Optional<String> projectRole = memberRepository.findRoleByProjectAndUser(projectId, userId);
        if (projectRole.isPresent()) return projectRole.get();

        boolean isCompanyAdmin = userRepository.findById(userId)
                .map(u -> "ADMIN".equals(u.getCompanyRole()) || "OWNER".equals(u.getCompanyRole()))
                .orElse(false);
        if (isCompanyAdmin) return "ADMIN";

        throw new AccessDeniedException("Not a member of this project");
    }
}
