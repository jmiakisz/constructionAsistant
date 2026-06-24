package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "role_configs")
@Getter @Setter @NoArgsConstructor
public class RoleConfig {

    @Id
    @Column(name = "key", length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "permission_level", nullable = false)
    private int permissionLevel;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
