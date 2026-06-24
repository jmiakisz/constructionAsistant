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
        member.setRole(Role.OWNER);
        memberRepository.save(member);

        return ProjectResponse.from(project, Role.OWNER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(Long userId) {
        return projectRepository.findByMemberUserId(userId).stream()
                .map(p -> {
                    Role role = memberRepository.findRoleByProjectAndUser(p.getId(), userId).orElse(Role.PODWYKONAWCA);
                    return ProjectResponse.from(p, role);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getForUser(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Role role = memberRepository.findRoleByProjectAndUser(projectId, userId)
                .orElseThrow(() -> new AccessDeniedException("Not a member of this project"));
        return ProjectResponse.from(project, role);
    }

    @Transactional
    public void addMember(Long projectId, Long requesterId, Long targetUserId, Role targetRole) {
        Role requesterRole = memberRepository.findRoleByProjectAndUser(projectId, requesterId)
                .orElseThrow(() -> new AccessDeniedException("Not a member"));
        if (!requesterRole.isAtLeast(Role.ADMIN)) {
            throw new AccessDeniedException("Only ADMIN+ can add members");
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Project project = projectRepository.getReferenceById(projectId);

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(target);
        member.setRole(targetRole);
        memberRepository.save(member);
    }

    public Role requireMembership(Long projectId, Long userId) {
        java.util.Optional<Role> projectRole = memberRepository.findRoleByProjectAndUser(projectId, userId);
        if (projectRole.isPresent()) return projectRole.get();

        boolean isCompanyAdmin = userRepository.findById(userId)
                .map(u -> "ADMIN".equals(u.getCompanyRole()) || "OWNER".equals(u.getCompanyRole()))
                .orElse(false);
        if (isCompanyAdmin) return Role.ADMIN;

        throw new AccessDeniedException("Not a member of this project");
    }
}
