package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AdminMfaRecoveryRequest;
import com.yak.zerotrust.dto.MfaStatusResponse;
import com.yak.zerotrust.security.LoginRateLimiter;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/mfa")
public class AdminMfaRecoveryController {

    private final MfaService mfaService;
    private final LoginRateLimiter loginRateLimiter;

    public AdminMfaRecoveryController(MfaService mfaService, LoginRateLimiter loginRateLimiter) {
        this.mfaService = mfaService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/recovery")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MfaStatusResponse> recoverMfa(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AdminMfaRecoveryRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        MfaStatusResponse response = mfaService.recoverMfaForAdmin(
                principal,
                request.targetUsername(),
                request.password(),
                request.code()
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
