package com.yak.zerotrust.controller;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.dto.AccessCheckRequest;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.ZeroTrustDecisionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/access")
public class AccessController {

    private final ZeroTrustDecisionService zeroTrustDecisionService;

    public AccessController(ZeroTrustDecisionService zeroTrustDecisionService) {
        this.zeroTrustDecisionService = zeroTrustDecisionService;
    }

    @PostMapping("/check")
    public AccessDecision checkAccess(
            @Valid @RequestBody AccessCheckRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        AccessContext context = new AccessContext(
                principal.getId(),
                principal.getUsername(),
                principal.getRole(),
                AccessChannel.API,
                request.deviceCode(),
                null,
                null,
                null,
                request.resource(),
                request.action(),
                null
        );
        return zeroTrustDecisionService.evaluate(context).decision();
    }
}
