package com.coass.dto.auth;

public record AuthResponse(
        String token,
        Long userId,
        String email,
        String name,
        String companyRole
) {}
