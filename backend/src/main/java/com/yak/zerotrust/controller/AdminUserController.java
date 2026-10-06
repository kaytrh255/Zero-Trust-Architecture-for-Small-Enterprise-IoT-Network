package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.UserAccountAuditResponse;
import com.yak.zerotrust.dto.UserAccountResponse;
import com.yak.zerotrust.dto.UserAccountUpdateRequest;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.UserAccountManagementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserAccountManagementService userAccountManagementService;

    public AdminUserController(UserAccountManagementService userAccountManagementService) {
        this.userAccountManagementService = userAccountManagementService;
    }

    @GetMapping
    public ResponseEntity<List<UserAccountResponse>> getAccounts() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(userAccountManagementService.getAccounts());
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserAccountResponse> updateAccount(
            @PathVariable Long id,
            @Valid @RequestBody UserAccountUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(userAccountManagementService.updateAccount(
                        id,
                        request.role(),
                        request.enabled(),
                        actor
                ));
    }

    @GetMapping("/audits")
    public ResponseEntity<AuditPageResponse<UserAccountAuditResponse>> getAudits(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "targetUsername", required = false) String targetUsername,
            @RequestParam(name = "actorUsername", required = false) String actorUsername
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(userAccountManagementService.searchAudits(
                        page, size, from, to, targetUsername, actorUsername
                ));
    }
}
