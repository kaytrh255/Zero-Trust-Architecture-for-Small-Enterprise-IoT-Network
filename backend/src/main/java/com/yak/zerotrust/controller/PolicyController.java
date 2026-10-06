package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.PolicyChangeAuditResponse;
import com.yak.zerotrust.dto.PolicyRequest;
import com.yak.zerotrust.dto.PolicyResponse;
import com.yak.zerotrust.entity.PolicyChangeOperation;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.PolicyService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public List<PolicyResponse> getAll() {
        return policyService.getAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public PolicyResponse getById(@PathVariable Long id) {
        return policyService.getById(id);
    }

    @GetMapping("/{id}/audits")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<PolicyChangeAuditResponse> getAudits(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "operation", required = false) PolicyChangeOperation operation,
            @RequestParam(name = "changedByUsername", required = false) String changedByUsername
    ) {
        return policyService.getAudits(id, page, size, from, to, operation, changedByUsername);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse create(
            @Valid @RequestBody PolicyRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return policyService.create(request, principal.getId(), principal.getUsername());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PolicyResponse update(
            @PathVariable Long id,
            @Valid @RequestBody PolicyRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return policyService.update(id, request, principal.getId(), principal.getUsername());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        policyService.delete(id, principal.getId(), principal.getUsername());
    }
}
