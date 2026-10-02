package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.MfaCodeRequest;
import com.yak.zerotrust.dto.MfaDisableRequest;
import com.yak.zerotrust.dto.MfaEnrollmentRequest;
import com.yak.zerotrust.dto.MfaEnrollmentResponse;
import com.yak.zerotrust.dto.MfaRecoveryCodeDisableRequest;
import com.yak.zerotrust.dto.MfaRecoveryCodeRotationRequest;
import com.yak.zerotrust.dto.MfaRecoveryCodesResponse;
import com.yak.zerotrust.dto.MfaRequiredEnrollmentCompletionResponse;
import com.yak.zerotrust.dto.MfaRequiredEnrollmentConfirmRequest;
import com.yak.zerotrust.dto.MfaRequiredEnrollmentRequest;
import com.yak.zerotrust.dto.MfaSecurityAuditResponse;
import com.yak.zerotrust.dto.MfaStatusResponse;
import com.yak.zerotrust.entity.MfaSecurityAuditOperation;
import com.yak.zerotrust.security.LoginRateLimiter;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.MfaSecurityAuditService;
import com.yak.zerotrust.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    private final MfaService mfaService;
    private final MfaSecurityAuditService auditService;
    private final LoginRateLimiter loginRateLimiter;

    public MfaController(
            MfaService mfaService,
            MfaSecurityAuditService auditService,
            LoginRateLimiter loginRateLimiter
    ) {
        this.mfaService = mfaService;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/required-enrollment")
    public ResponseEntity<MfaEnrollmentResponse> beginRequiredEnrollment(
            @Valid @RequestBody MfaRequiredEnrollmentRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.beginRequiredEnrollment(request.enrollmentToken()));
    }

    @PostMapping("/required-enrollment/confirm")
    public ResponseEntity<MfaRequiredEnrollmentCompletionResponse> confirmRequiredEnrollment(
            @Valid @RequestBody MfaRequiredEnrollmentConfirmRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        Optional<MfaRequiredEnrollmentCompletionResponse> completion =
                mfaService.confirmRequiredEnrollment(request.enrollmentToken(), request.code());
        return noStore(completion.orElseThrow(() -> new BadCredentialsException("Invalid authentication code")));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaStatusResponse> status(@AuthenticationPrincipal UserPrincipal principal) {
        return noStore(mfaService.status(principal));
    }

    @PostMapping("/enrollment")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaEnrollmentResponse> beginEnrollment(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MfaEnrollmentRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.beginEnrollment(principal, request.password()));
    }

    @PostMapping("/enrollment/confirm")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaRecoveryCodesResponse> confirmEnrollment(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MfaCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.confirmEnrollment(principal, request.code()));
    }

    @PostMapping("/recovery-codes/rotate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaRecoveryCodesResponse> rotateRecoveryCodes(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MfaRecoveryCodeRotationRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.rotateRecoveryCodes(principal, request.password(), request.code()));
    }

    @PostMapping("/disable")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaStatusResponse> disable(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MfaDisableRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.disable(principal, request.password(), request.code()));
    }

    @PostMapping("/disable/recovery-code")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<MfaStatusResponse> disableWithRecoveryCode(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MfaRecoveryCodeDisableRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.checkAndRecord(servletRequest.getRemoteAddr());
        return noStore(mfaService.disableWithRecoveryCode(principal, request.password(), request.recoveryCode()));
    }

    @GetMapping("/audits")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public ResponseEntity<AuditPageResponse<MfaSecurityAuditResponse>> audits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) MfaSecurityAuditOperation operation,
            @RequestParam(required = false) String username
    ) {
        return noStore(auditService.search(page, size, from, to, operation, username));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
