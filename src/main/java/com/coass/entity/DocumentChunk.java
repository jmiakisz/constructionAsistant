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

    // Przechowujemy embedding jako string "[0.1, 0.2, ...]" — pgvector akceptuje taki format
    @Column(columnDefinition = "vector(384)")
    private String embedding;

    private Integer pageNumber;

    @Column(nullable = false)
    private Integer chunkIndex;
}
