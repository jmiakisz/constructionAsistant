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

    // Statystyki tokenów globalnie
    @Query(value = """
        SELECT
            COUNT(*) FILTER (WHERE role = 'assistant') AS total_messages,
            COALESCE(SUM(input_tokens), 0)  AS total_input_tokens,
            COALESCE(SUM(output_tokens), 0) AS total_output_tokens,
            COALESCE(SUM(cost_usd), 0)      AS total_cost_usd,
            COALESCE(SUM(input_tokens) FILTER (WHERE created_at >= NOW() - INTERVAL '1 day'), 0) AS today_input_tokens,
            COALESCE(SUM(output_tokens) FILTER (WHERE created_at >= NOW() - INTERVAL '1 day'), 0) AS today_output_tokens,
            COALESCE(SUM(cost_usd) FILTER (WHERE created_at >= NOW() - INTERVAL '1 day'), 0) AS today_cost_usd,
            COALESCE(SUM(input_tokens) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0) AS month_input_tokens,
            COALESCE(SUM(output_tokens) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0) AS month_output_tokens,
            COALESCE(SUM(cost_usd) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0) AS month_cost_usd
        FROM messages
        """, nativeQuery = true)
    List<Object[]> getTokenStats();

    // Statystyki per model
    @Query(value = """
        SELECT model,
               COUNT(*) AS messages,
               COALESCE(SUM(input_tokens), 0)  AS input_tokens,
               COALESCE(SUM(output_tokens), 0) AS output_tokens,
               COALESCE(SUM(cost_usd), 0)      AS cost_usd
        FROM messages
        WHERE model IS NOT NULL
        GROUP BY model
        ORDER BY cost_usd DESC
        """, nativeQuery = true)
    List<Object[]> getStatsByModel();

    // Statystyki per projekt z liczbą konwersacji i userów
    @Query(value = """
        SELECT p.id, p.name,
               COUNT(DISTINCT c.id)                              AS conversations,
               COUNT(DISTINCT c.user_id)                        AS active_users,
               COUNT(m.id) FILTER (WHERE m.role = 'assistant')  AS messages,
               COALESCE(SUM(m.input_tokens), 0)                 AS input_tokens,
               COALESCE(SUM(m.output_tokens), 0)                AS output_tokens,
               COALESCE(SUM(m.cost_usd), 0)                     AS cost_usd
        FROM messages m
        JOIN conversations c ON m.conversation_id = c.id
        JOIN projects p ON c.project_id = p.id
        GROUP BY p.id, p.name
        ORDER BY cost_usd DESC
        """, nativeQuery = true)
    List<Object[]> getStatsByProject();

    // Statystyki per konwersacja
    @Query(value = """
        SELECT c.id, c.title, p.name AS project_name,
               u.name AS user_name,
               COUNT(m.id) FILTER (WHERE m.role = 'assistant') AS messages,
               COALESCE(SUM(m.input_tokens), 0)                AS input_tokens,
               COALESCE(SUM(m.output_tokens), 0)               AS output_tokens,
               COALESCE(SUM(m.cost_usd), 0)                    AS cost_usd,
               MIN(m.created_at)                               AS started_at,
               MAX(m.created_at)                               AS last_message_at
        FROM conversations c
        JOIN projects p ON c.project_id = p.id
        JOIN users u ON c.user_id = u.id
        LEFT JOIN messages m ON m.conversation_id = c.id
        GROUP BY c.id, c.title, p.name, u.name
        ORDER BY cost_usd DESC
        LIMIT 100
        """, nativeQuery = true)
    List<Object[]> getStatsByConversation();

    // Zużycie per dzień (ostatnie 30 dni)
    @Query(value = """
        SELECT DATE(created_at) AS day,
               COALESCE(SUM(input_tokens), 0)  AS input_tokens,
               COALESCE(SUM(output_tokens), 0) AS output_tokens,
               COALESCE(SUM(cost_usd), 0)      AS cost_usd
        FROM messages
        WHERE created_at >= NOW() - INTERVAL '30 days'
        GROUP BY DATE(created_at)
        ORDER BY day
        """, nativeQuery = true)
    List<Object[]> getDailyStats();

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
