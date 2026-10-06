package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.AuthenticationAttemptAuditResponse;
import com.yak.zerotrust.entity.AuthenticationAttemptOutcome;
import com.yak.zerotrust.service.AuthenticationAuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth/audits")
public class AuthenticationAuditController {

    private final AuthenticationAuditService authenticationAuditService;

    public AuthenticationAuditController(AuthenticationAuditService authenticationAuditService) {
        this.authenticationAuditService = authenticationAuditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<AuthenticationAttemptAuditResponse> search(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "outcome", required = false) AuthenticationAttemptOutcome outcome,
            @RequestParam(name = "username", required = false) String username
    ) {
        return authenticationAuditService.search(page, size, from, to, outcome, username);
    }
}
