package com.coass.controller;

import com.coass.entity.Document;
import com.coass.entity.DocumentFolder;
import com.coass.repository.DocumentFolderRepository;
import com.coass.repository.DocumentRepository;
import com.coass.repository.ProjectRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.ProjectService;
import com.coass.service.RoleConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/folders")
@RequiredArgsConstructor
public class DocumentFolderController {

    private final DocumentFolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final RoleConfigService roleConfigService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {

        String userRoleKey = projectService.requireMembership(projectId, user.getUserId());
        List<DocumentFolder> allFolders = folderRepository.findByProjectId(projectId);

        if (roleConfigService.isAtLeast(userRoleKey, "INZYNIER")) {
            return ResponseEntity.ok(allFolders.stream().map(this::toMap).toList());
        }

        Map<Long, Long> parentOf = allFolders.stream()
                .filter(f -> f.getParent() != null)
                .collect(Collectors.toMap(DocumentFolder::getId, f -> f.getParent().getId()));

        Set<Long> visibleIds = new HashSet<>();
        documentRepository.findByProjectId(projectId).stream()
                .filter(d -> canSee(d, userRoleKey) && d.getFolder() != null)
                .forEach(d -> {
                    Long fId = d.getFolder().getId();
                    while (fId != null && visibleIds.add(fId)) {
                        fId = parentOf.get(fId);
                    }
                });

        return ResponseEntity.ok(
                allFolders.stream()
                        .filter(f -> visibleIds.contains(f.getId()))
                        .map(this::toMap)
                        .toList()
        );
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        projectService.requireMembership(projectId, user.getUserId());

        String name = (String) body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().build();

        DocumentFolder folder = new DocumentFolder();
        folder.setProject(projectRepository.getReferenceById(projectId));
        folder.setName(name.strip());

        Object parentIdRaw = body.get("parentId");
        if (parentIdRaw != null) {
            Long parentId = Long.parseLong(parentIdRaw.toString());
            DocumentFolder parent = folderRepository.findById(parentId)
                    .filter(f -> f.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new IllegalArgumentException("Parent folder not found in this project"));
            folder.setParent(parent);
        }

        return ResponseEntity.ok(toMap(folderRepository.save(folder)));
    }

    @PatchMapping("/{folderId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        projectService.requireMembership(projectId, user.getUserId());

        DocumentFolder folder = folderRepository.findById(folderId)
                .filter(f -> f.getProject().getId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        if (body.containsKey("name")) {
            String name = (String) body.get("name");
            if (name != null && !name.isBlank()) folder.setName(name.strip());
        }

        if (body.containsKey("parentId")) {
            Object raw = body.get("parentId");
            if (raw == null) {
                folder.setParent(null);
            } else {
                Long parentId = Long.parseLong(raw.toString());
                if (parentId.equals(folderId))
                    return ResponseEntity.badRequest().build();
                DocumentFolder parent = folderRepository.findById(parentId)
                        .filter(f -> f.getProject().getId().equals(projectId))
                        .orElseThrow(() -> new IllegalArgumentException("Parent folder not found"));
                folder.setParent(parent);
            }
        }

        return ResponseEntity.ok(toMap(folderRepository.save(folder)));
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long projectId,
            @PathVariable Long folderId,
            @RequestParam(defaultValue = "false") boolean force,
            @AuthenticationPrincipal CoassUserDetails user) {

        projectService.requireMembership(projectId, user.getUserId());

        DocumentFolder folder = folderRepository.findById(folderId)
                .filter(f -> f.getProject().getId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        if (!force) {
            long subfolders = folderRepository.countByParentId(folderId);
            long docs = documentRepository.countByFolderId(folderId);
            if (subfolders > 0 || docs > 0)
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        folderRepository.delete(folder);
        return ResponseEntity.noContent().build();
    }

    private boolean canSee(Document doc, String userRoleKey) {
        String[] roles = doc.getVisibleForRoles();
        if (roles == null || roles.length == 0) return true;
        int userLevel = roleConfigService.getLevel(userRoleKey);
        int minLevel = Integer.MAX_VALUE;
        for (String rk : roles) {
            int lvl = roleConfigService.getLevel(rk);
            if (lvl < minLevel) minLevel = lvl;
        }
        return userLevel >= minLevel;
    }

    private Map<String, Object> toMap(DocumentFolder f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("parentId", f.getParent() != null ? f.getParent().getId() : null);
        m.put("projectId", f.getProject().getId());
        m.put("createdAt", f.getCreatedAt().toString());
        return m;
    }
}
