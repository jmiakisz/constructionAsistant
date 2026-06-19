package com.coass.controller;

import com.coass.dto.document.DocumentResponse;
import com.coass.entity.AiIndexingMode;
import com.coass.entity.DocumentType;
import com.coass.entity.Role;
import com.coass.security.CoassUserDetails;
import com.coass.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable Long projectId,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "INNE") DocumentType documentType,
            @RequestParam(defaultValue = "CHUNKS_ONLY") AiIndexingMode aiIndexingMode,
            @RequestParam(required = false) Role minRole,
            @AuthenticationPrincipal CoassUserDetails user) throws IOException {

        return ResponseEntity.ok(
                documentService.upload(projectId, user.getUserId(), file, documentType, aiIndexingMode, minRole)
        );
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<DocumentResponse>> uploadBulk(
            @PathVariable Long projectId,
            @RequestParam List<MultipartFile> files,
            @RequestParam(defaultValue = "INNE") DocumentType documentType,
            @RequestParam(defaultValue = "CHUNKS_ONLY") AiIndexingMode aiIndexingMode,
            @RequestParam(required = false) Role minRole,
            @AuthenticationPrincipal CoassUserDetails user) throws IOException {

        return ResponseEntity.ok(
                documentService.uploadBulk(projectId, user.getUserId(), files, documentType, aiIndexingMode, minRole)
        );
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CoassUserDetails user) {

        return ResponseEntity.ok(documentService.listForProject(projectId, user.getUserId()));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            @AuthenticationPrincipal CoassUserDetails user) {

        return ResponseEntity.ok(documentService.get(documentId, user.getUserId()));
    }
}
