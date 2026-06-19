package com.coass.repository;

import com.coass.entity.UserStyleObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserStyleObservationRepository extends JpaRepository<UserStyleObservation, Long> {

    @Query("SELECT o FROM UserStyleObservation o WHERE o.user.id = :userId AND o.createdAt >= :since ORDER BY o.createdAt DESC")
    List<UserStyleObservation> findRecentByUserId(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    void deleteByCreatedAtBefore(LocalDateTime cutoff);
}
