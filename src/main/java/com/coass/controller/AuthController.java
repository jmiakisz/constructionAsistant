package com.coass.controller;

import com.coass.dto.auth.AuthResponse;
import com.coass.dto.auth.LoginRequest;
import com.coass.dto.auth.RegisterRequest;
import com.coass.security.CoassUserDetails;
import com.coass.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest req,
            @AuthenticationPrincipal CoassUserDetails caller) {
        String callerRole = caller != null ? caller.getCompanyRole() : null;
        return ResponseEntity.ok(authService.register(req, callerRole));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }
}
