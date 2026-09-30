package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.DeviceOwnerRequest;
import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.dto.DeviceResponse;
import com.yak.zerotrust.dto.DeviceStatusRequest;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceProvisioningResponse create(
            @Valid @RequestBody DeviceRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return deviceService.create(request, principal.getId());
    }

    @PostMapping("/{id}/credentials/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceProvisioningResponse rotateMqttCredential(@PathVariable Long id) {
        return deviceService.rotateMqttCredential(id);
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
            @Valid @RequestBody DeviceStatusRequest request
    ) {
        return deviceService.updateStatus(id, request.status());
    }

    @PatchMapping("/{id}/owner")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse transferOwnership(
            @PathVariable Long id,
            @Valid @RequestBody DeviceOwnerRequest request
    ) {
        return deviceService.transferOwnership(id, request.ownerUsername());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        deviceService.revoke(id);
    }
}
