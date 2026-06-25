package com.coass.controller;

import com.coass.dto.document.DocumentResponse;
import com.coass.entity.AiIndexingMode;
import com.coass.entity.ProjectAlert;
import com.coass.repository.ProjectAlertRepository;
import com.coass.security.CoassUserDetails;
import com.coass.service.DocumentService;
import com.coass.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final ProjectAlertRepository alertRepository;
    private final ProjectService projectService;

    // ── Alerts ──────────────────────────────────────────────────────────────────

    @GetMapping("/documents/{documentId}/alerts")
    public ResponseEntity<List<Map<String, Object>>> getDocumentAlerts(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {

        projectService.requireMembership(projectId, user.getUserId());
        List<Map<String, Object>> result = alertRepository
                .findByProjectIdAndDocumentIdOrderByCreatedAtDesc(projectId, documentId)
                .stream()
                .map(a -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("level", a.getLevel());
                    m.put("message", a.getMessage());
                    m.put("documentId", documentId);
                    m.put("createdAt", a.getCreatedAt().toString());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    // ── Documents ────────────────────────────────────────────────────────────────

    @PostMapping("/documents")
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable Long projectId,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "INNE") String documentType,
            @RequestParam(defaultValue = "CHUNKS_ONLY") AiIndexingMode aiIndexingMode,
            @RequestParam(required = false) String minRole,
            @AuthenticationPrincipal CoassUserDetails user) throws IOException {

        return ResponseEntity.ok(
                documentService.upload(projectId, user.getUserId(), file, documentType, aiIndexingMode, minRole)
        );
    }

    @PostMapping("/documents/bulk")
    public ResponseEntity<List<DocumentResponse>> uploadBulk(
            @PathVariable Long projectId,
            @RequestParam List<MultipartFile> files,
            @RequestParam(defaultValue = "INNE") String documentType,
            @RequestParam(defaultValue = "CHUNKS_ONLY") AiIndexingMode aiIndexingMode,
            @RequestParam(required = false) String minRole,
            @RequestParam(required = false) Long folderId,
            @AuthenticationPrincipal CoassUserDetails user) throws IOException {

        return ResponseEntity.ok(
                documentService.uploadBulk(projectId, user.getUserId(), files, documentType, aiIndexingMode, minRole, folderId)
        );
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentResponse>> list(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {

        return ResponseEntity.ok(documentService.listForProject(projectId, user.getUserId()));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {

        return ResponseEntity.ok(documentService.get(documentId, user.getUserId()));
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "false") boolean inline,
            @AuthenticationPrincipal CoassUserDetails user) throws IOException {

        DocumentService.FileResult result = documentService.resolveFile(documentId, user.getUserId());

        String ct = Files.probeContentType(result.path());
        if (ct == null) ct = detectContentType(result.originalName());

        String encoded = UriUtils.encode(result.originalName(), StandardCharsets.UTF_8);
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(ct))
                .body(new FileSystemResource(result.path()));
    }

    private String detectContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".doc"))  return "application/msword";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls"))  return "application/vnd.ms-excel";
        return "application/octet-stream";
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {
        documentService.deleteDocument(documentId, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/documents/{documentId}/reprocess")
    public ResponseEntity<DocumentResponse> reprocess(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {
        return ResponseEntity.ok(documentService.reprocess(documentId, user.getUserId()));
    }

    @PostMapping("/documents/{documentId}/archive")
    public ResponseEntity<Void> archive(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {
        documentService.archiveDocument(documentId, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/documents/{documentId}/folder")
    public ResponseEntity<DocumentResponse> moveToFolder(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CoassUserDetails user) {

        Long folderId = body.get("folderId") != null
                ? Long.parseLong(body.get("folderId").toString()) : null;
        return ResponseEntity.ok(
                documentService.moveToFolder(documentId, projectId, user.getUserId(), folderId)
        );
    }
}
