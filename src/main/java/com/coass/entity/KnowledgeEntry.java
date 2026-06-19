package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_entries")
@Getter @Setter @NoArgsConstructor
public class KnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project; // null = wiedza firmowa (cross-project)

    @Column(nullable = false, length = 500)
    private String content;

    @Column(columnDefinition = "vector(384)")
    private String embedding;

    @Column(length = 50)
    private String sourceRole;

    @Column(length = 50)
    private String category; // TECHNICZNA | FINANSOWA | PODWYKONAWCY | MATERIALY

    @Column(nullable = false)
    private int confidence = 1;

    @Column(nullable = false, length = 20)
    private String entryType = "PERMANENT"; // PERMANENT | TEMPORAL

    private LocalDateTime validUntil;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime lastConfirmedAt = LocalDateTime.now();
}
