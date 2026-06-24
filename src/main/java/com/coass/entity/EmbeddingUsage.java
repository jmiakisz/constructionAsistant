package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "embedding_usage")
@Getter @Setter @NoArgsConstructor
public class EmbeddingUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String source; // CHAT_QUERY | DOCUMENT_CHUNK | KNOWLEDGE_ENTRY

    @Column(nullable = false)
    private int textLength;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
