package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceOwnerRequest;
import com.yak.zerotrust.dto.DeviceCredentialAuditResponse;
import com.yak.zerotrust.dto.DeviceOwnershipAuditResponse;
import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.dto.DeviceResponse;
import com.yak.zerotrust.dto.DeviceStatusAuditResponse;
import com.yak.zerotrust.dto.DeviceStatusRequest;
import com.yak.zerotrust.entity.DeviceCredentialOperation;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public List<DeviceResponse> getAll() {
        return deviceService.getAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public DeviceResponse getById(@PathVariable Long id) {
        return deviceService.getById(id);
    }

    @GetMapping("/{id}/ownership-audits")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<DeviceOwnershipAuditResponse> getOwnershipAudits(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "changedByUsername", required = false) String changedByUsername,
            @RequestParam(name = "newOwnerUsername", required = false) String newOwnerUsername
    ) {
        return deviceService.getOwnershipAudits(
                id, page, size, from, to, changedByUsername, newOwnerUsername
        );
    }

    @GetMapping("/{id}/status-audits")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<DeviceStatusAuditResponse> getStatusAudits(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "newStatus", required = false) DeviceStatus newStatus,
            @RequestParam(name = "changedByUsername", required = false) String changedByUsername
    ) {
        return deviceService.getStatusAudits(
                id, page, size, from, to, newStatus, changedByUsername
        );
    }

    @GetMapping("/{id}/credential-audits")
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public AuditPageResponse<DeviceCredentialAuditResponse> getCredentialAudits(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "operation", required = false) DeviceCredentialOperation operation,
            @RequestParam(name = "changedByUsername", required = false) String changedByUsername
    ) {
        return deviceService.getCredentialAudits(
                id, page, size, from, to, operation, changedByUsername
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DeviceProvisioningResponse> create(
            @Valid @RequestBody DeviceRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(deviceService.create(request, principal.getId(), principal.getUsername()));
    }

    @PostMapping("/{id}/credentials/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DeviceProvisioningResponse> rotateMqttCredential(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deviceService.rotateMqttCredential(id, principal.getId(), principal.getUsername()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse update(@PathVariable Long id, @Valid @RequestBody DeviceRequest request) {
        return deviceService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody DeviceStatusRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return deviceService.updateStatus(
                id,
                request.status(),
                principal.getId(),
                principal.getUsername()
        );
    }

    @PatchMapping("/{id}/owner")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse transferOwnership(
            @PathVariable Long id,
            @Valid @RequestBody DeviceOwnerRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return deviceService.transferOwnership(
                id,
                request.ownerUsername(),
                principal.getId(),
                principal.getUsername()
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        deviceService.revoke(id, principal.getId(), principal.getUsername());
    }
}
