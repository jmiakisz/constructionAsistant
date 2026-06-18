package com.coass.repository;

import com.coass.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByProjectIdAndUserIdOrderByCreatedAtDesc(Long projectId, Long userId);

    Optional<Conversation> findFirstByProjectIdAndUserIdOrderByCreatedAtDesc(Long projectId, Long userId);
}
