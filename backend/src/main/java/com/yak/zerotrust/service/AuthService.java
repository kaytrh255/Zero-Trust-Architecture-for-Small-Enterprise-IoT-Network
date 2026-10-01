package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuthResponse;
import com.yak.zerotrust.dto.LoginRequest;
import com.yak.zerotrust.dto.RegisterRequest;
import com.yak.zerotrust.dto.UserResponse;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.UsernameAlreadyExistsException;
import com.yak.zerotrust.repository.UserRepository;
import com.yak.zerotrust.security.JwtService;
import com.yak.zerotrust.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthenticationAuditService authenticationAuditService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            AuthenticationAuditService authenticationAuditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.authenticationAuditService = authenticationAuditService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException();
        }

        UserAccount user = new UserAccount(
                username,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                UserRole.USER,
                true
        );
        return toResponse(userRepository.save(user));
    }

    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.username());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password())
            );
        } catch (AuthenticationException exception) {
            authenticationAuditService.recordFailedLogin(username);
            throw exception;
        }

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getUsername());
        authenticationAuditService.recordSuccessfulLogin(principal);
        return new AuthResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds(),
                toResponse(principal)
        );
    }

    public UserResponse currentUser(UserPrincipal principal) {
        return toResponse(principal);
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toResponse(UserAccount user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.isEnabled());
    }

    private UserResponse toResponse(UserPrincipal principal) {
        return new UserResponse(
                principal.getId(),
                principal.getUsername(),
                principal.getFullName(),
                principal.getRole(),
                principal.isEnabled()
        );
    }
}
