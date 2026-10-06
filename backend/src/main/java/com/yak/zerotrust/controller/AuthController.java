package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuthResponse;
import com.yak.zerotrust.dto.LoginRequest;
import com.yak.zerotrust.dto.MfaVerifyRequest;
import com.yak.zerotrust.dto.RegisterRequest;
import com.yak.zerotrust.dto.UserResponse;
import com.yak.zerotrust.security.LoginRateLimiter;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.login(request));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthResponse> verifyMfa(
            @Valid @RequestBody MfaVerifyRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.verifyMfa(request.mfaToken(), request.code()));
    }

    @GetMapping("/me")
    public UserResponse currentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal);
    }
}
