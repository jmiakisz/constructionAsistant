package com.coass.repository;

import com.coass.entity.RoleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleConfigRepository extends JpaRepository<RoleConfig, String> {
    List<RoleConfig> findAllByOrderBySortOrderAsc();
}
