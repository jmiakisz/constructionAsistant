package com.coass.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_type_configs")
@Getter @Setter @NoArgsConstructor
public class DocumentTypeConfig {

    @Id
    @Column(name = "key", length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
