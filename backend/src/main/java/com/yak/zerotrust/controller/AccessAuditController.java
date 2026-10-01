package com.yak.zerotrust.controller;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.dto.AccessAuditResponse;
import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.service.AccessAuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/access/audits")
public class AccessAuditController {

    private final AccessAuditService accessAuditService;

    public AccessAuditController(AccessAuditService accessAuditService) {
        this.accessAuditService = accessAuditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<AccessAuditResponse> search(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "decision", required = false) AccessDecisionOutcome decision,
            @RequestParam(name = "reason", required = false) AccessDecisionReason reason,
            @RequestParam(name = "channel", required = false) AccessChannel channel,
            @RequestParam(name = "action", required = false) PolicyAction action,
            @RequestParam(name = "deviceCode", required = false) String deviceCode,
            @RequestParam(name = "requesterUsername", required = false) String requesterUsername,
            @RequestParam(name = "resource", required = false) String resource
    ) {
        return accessAuditService.search(
                page,
                size,
                from,
                to,
                decision,
                reason,
                channel,
                deviceCode,
                requesterUsername,
                resource,
                action
        );
    }
}
