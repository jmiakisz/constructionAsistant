package com.coass.service;

import com.coass.dto.document.DocumentResponse;
import com.coass.entity.*;
import com.coass.repository.DocumentChunkRepository;
import com.coass.repository.DocumentRepository;
import com.coass.repository.ProjectRepository;
import com.coass.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DocumentProcessingService processingService;
    private final ProjectService projectService;
    private final com.coass.repository.DocumentFolderRepository folderRepository;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Transactional
    public DocumentResponse upload(Long projectId, Long userId, MultipartFile file,
                                   DocumentType documentType, AiIndexingMode indexingMode,
                                   Role minRole) throws IOException {
        Role userRole = projectService.requireMembership(projectId, userId);
        final Role effectiveRole = (minRole != null) ? minRole : userRole;

        Path dir = Paths.get(uploadDir, projectId.toString());
        Files.createDirectories(dir);

        String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = dir.resolve(filename);
        file.transferTo(filePath);

        Document doc = new Document();
        doc.setProject(projectRepository.getReferenceById(projectId));
        doc.setUploadedBy(userRepository.getReferenceById(userId));
        doc.setName(file.getOriginalFilename());
        doc.setFilePath(filePath.toString());
        doc.setDocumentType(documentType);
        doc.setAiIndexingMode(indexingMode);
        String[] visibleRoles = java.util.Arrays.stream(Role.values())
                .filter(r -> r.isAtLeast(effectiveRole))
                .map(Enum::name)
                .toArray(String[]::new);
        doc.setVisibleForRoles(visibleRoles);
        doc.setStatus(DocumentStatus.PROCESSING.name());
        documentRepository.save(doc);

        if (indexingMode != AiIndexingMode.NONE) {
            processingService.process(doc.getId(), filePath);
        } else {
            doc.setStatus(DocumentStatus.READY.name());
            documentRepository.save(doc);
        }

        return DocumentResponse.from(doc);
    }

    public List<DocumentResponse> uploadBulk(Long projectId, Long userId, List<MultipartFile> files,
                                              DocumentType documentType, AiIndexingMode indexingMode,
                                              Role minRole, Long folderId) throws IOException {
        List<DocumentResponse> results = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            DocumentResponse resp = upload(projectId, userId, file, documentType, indexingMode, minRole);
            if (folderId != null) {
                results.add(moveToFolder(resp.id(), projectId, userId, folderId));
            } else {
                results.add(resp);
            }
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listForProject(Long projectId, Long userId) {
        Role userRole = projectService.requireMembership(projectId, userId);
        return documentRepository.findByProjectId(projectId).stream()
                .filter(doc -> !DocumentStatus.ARCHIVED.name().equals(doc.getStatus()))
                .filter(doc -> canSee(doc, userRole))
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        Role userRole = projectService.requireMembership(doc.getProject().getId(), userId);

        if (!canSee(doc, userRole)) throw new AccessDeniedException("No access to this document");

        return DocumentResponse.from(doc);
    }

    @Transactional
    public DocumentResponse moveToFolder(Long documentId, Long projectId, Long userId, Long folderId) {
        projectService.requireMembership(projectId, userId);
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        if (folderId == null) {
            doc.setFolder(null);
        } else {
            com.coass.entity.DocumentFolder folder = folderRepository.findById(folderId)
                    .filter(f -> f.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new IllegalArgumentException("Folder not found"));
            doc.setFolder(folder);
        }
        return DocumentResponse.from(documentRepository.save(doc));
    }

    @Transactional
    public void deleteDocument(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        projectService.requireMembership(doc.getProject().getId(), userId);
        chunkRepository.deleteByDocumentId(documentId);
        try { Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception ignored) {}
        documentRepository.delete(doc);
    }

    @Transactional
    public void archiveDocument(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        projectService.requireMembership(doc.getProject().getId(), userId);
        chunkRepository.deleteByDocumentId(documentId);
        doc.setStatus(DocumentStatus.ARCHIVED.name());
        documentRepository.save(doc);
    }

    public record FileResult(Path path, String originalName) {}

    @Transactional(readOnly = true)
    public FileResult resolveFile(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        Role userRole = projectService.requireMembership(doc.getProject().getId(), userId);
        if (!canSee(doc, userRole)) throw new AccessDeniedException("No access to document");
        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) throw new IllegalArgumentException("File not found on disk");
        return new FileResult(path, doc.getName());
    }

    private boolean canSee(Document doc, Role userRole) {
        String[] roles = doc.getVisibleForRoles();
        if (roles == null || roles.length == 0) return true;
        Role minRole = Role.valueOf(roles[0]);
        return userRole.isAtLeast(minRole);
    }
}
