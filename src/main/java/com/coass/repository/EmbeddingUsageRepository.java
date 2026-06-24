package com.coass.repository;

import com.coass.entity.EmbeddingUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EmbeddingUsageRepository extends JpaRepository<EmbeddingUsage, Long> {

    // Totals per source
    @Query(value = """
        SELECT source,
               COUNT(*)           AS calls,
               COALESCE(SUM(text_length), 0) AS total_chars,
               COALESCE(SUM(text_length) FILTER (WHERE created_at >= NOW() - INTERVAL '1 day'), 0) AS today_chars,
               COALESCE(SUM(text_length) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0) AS month_chars
        FROM embedding_usage
        GROUP BY source
        ORDER BY calls DESC
        """, nativeQuery = true)
    List<Object[]> getStatsBySource();

    // Per projekt
    @Query(value = """
        SELECT p.id, p.name,
               COUNT(eu.id)                   AS calls,
               COALESCE(SUM(eu.text_length), 0) AS total_chars
        FROM embedding_usage eu
        JOIN projects p ON eu.project_id = p.id
        GROUP BY p.id, p.name
        ORDER BY calls DESC
        """, nativeQuery = true)
    List<Object[]> getStatsByProject();

    // Per dzień (ostatnie 30 dni)
    @Query(value = """
        SELECT DATE(created_at) AS day,
               COUNT(*)         AS calls,
               SUM(text_length) AS total_chars
        FROM embedding_usage
        WHERE created_at >= NOW() - INTERVAL '30 days'
        GROUP BY DATE(created_at)
        ORDER BY day
        """, nativeQuery = true)
    List<Object[]> getDailyStats();
}
