package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Getter @Setter @NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    private String role; // USER | ASSISTANT

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Integer tokensUsed;

    @Column(nullable = false)
    private boolean processedForKnowledge = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
