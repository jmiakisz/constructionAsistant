package com.coass.entity;

public enum Role {
    PODWYKONAWCA(1),
    BRYGADZISTA(2),
    INZYNIER(3),
    KOSZTORYSANT(3),
    KIEROWNIK(4),
    ADMIN(5),
    OWNER(6);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    public boolean isAtLeast(Role required) {
        return this.level >= required.level;
    }
}
