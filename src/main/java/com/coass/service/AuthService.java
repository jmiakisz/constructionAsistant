package com.coass.service;

import com.coass.dto.auth.AuthResponse;
import com.coass.dto.auth.LoginRequest;
import com.coass.dto.auth.RegisterRequest;
import com.coass.entity.User;
import com.coass.repository.UserRepository;
import com.coass.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authManager;

    @Transactional
    public AuthResponse register(RegisterRequest req, String callerCompanyRole) {
        boolean isBootstrap = userRepository.count() == 0;

        if (!isBootstrap) {
            if (callerCompanyRole == null) {
                throw new org.springframework.security.access.AccessDeniedException("Authentication required");
            }
            if (!callerCompanyRole.equals("OWNER") && !callerCompanyRole.equals("ADMIN")) {
                throw new org.springframework.security.access.AccessDeniedException("Only OWNER or ADMIN can create accounts");
            }
        }

        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already in use");
        }

        User user = new User();
        user.setEmail(req.email());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setName(req.name());
        user.setCompanyRole(isBootstrap ? "OWNER" : "MEMBER");
        userRepository.save(user);

        String token = jwtUtil.generate(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getName());
    }

    public AuthResponse login(LoginRequest req) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        User user = userRepository.findByEmail(req.email()).orElseThrow();
        String token = jwtUtil.generate(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getName());
    }
}
