package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_chunks")
@Getter @Setter @NoArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // wypełniane w Etapie 3 przez natywne SQL — pgvector nie obsługuje standardowego JDBC type mapping
    @Column(columnDefinition = "vector(384)", insertable = false, updatable = false)
    private String embedding;

    private Integer pageNumber;

    @Column(nullable = false)
    private Integer chunkIndex;
}
