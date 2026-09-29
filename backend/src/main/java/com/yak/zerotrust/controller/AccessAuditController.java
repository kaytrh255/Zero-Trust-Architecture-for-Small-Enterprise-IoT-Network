package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AccessAuditResponse;
import com.yak.zerotrust.service.AccessAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/access/audits")
public class AccessAuditController {

    private final AccessAuditService accessAuditService;

    public AccessAuditController(AccessAuditService accessAuditService) {
        this.accessAuditService = accessAuditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public List<AccessAuditResponse> getRecent() {
        return accessAuditService.getRecent();
    }
}
