package com.coass.repository;

import com.coass.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    // Ostatnie N wiadomości dla kontekstu
    @Query(value = """
        SELECT * FROM messages WHERE conversation_id = :convId
        ORDER BY created_at DESC LIMIT :limit
        """, nativeQuery = true)
    List<Message> findLastN(@Param("convId") Long conversationId, @Param("limit") int limit);

    long countByConversationId(Long conversationId);

    // Najstarsze N wiadomości do compactingu
    @Query(value = """
        SELECT * FROM messages WHERE conversation_id = :convId
        ORDER BY created_at ASC LIMIT :limit
        """, nativeQuery = true)
    List<Message> findOldestN(@Param("convId") Long conversationId, @Param("limit") int limit);

    // Do agenta nocnego
    @Query("SELECT m FROM Message m WHERE m.processedForKnowledge = false AND m.createdAt >= :since")
    List<Message> findUnprocessedSince(@Param("since") LocalDateTime since);
}
