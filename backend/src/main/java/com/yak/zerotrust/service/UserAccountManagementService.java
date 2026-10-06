package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.UserAccountAuditResponse;
import com.yak.zerotrust.dto.UserAccountResponse;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserAccountAudit;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.InvalidUserManagementException;
import com.yak.zerotrust.exception.UserNotFoundException;
import com.yak.zerotrust.repository.MfaLoginChallengeRepository;
import com.yak.zerotrust.repository.UserAccountAuditRepository;
import com.yak.zerotrust.repository.UserRepository;
import com.yak.zerotrust.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserAccountManagementService {

    private final UserRepository userRepository;
    private final UserAccountAuditRepository auditRepository;
    private final MfaLoginChallengeRepository mfaLoginChallengeRepository;

    public UserAccountManagementService(
            UserRepository userRepository,
            UserAccountAuditRepository auditRepository,
            MfaLoginChallengeRepository mfaLoginChallengeRepository
    ) {
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.mfaLoginChallengeRepository = mfaLoginChallengeRepository;
    }

    @Transactional(readOnly = true)
    public List<UserAccountResponse> getAccounts() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(this::toAccountResponse)
                .toList();
    }

    @Transactional
    public UserAccountResponse updateAccount(
            Long targetUserId,
            UserRole requestedRole,
            boolean requestedEnabled,
            UserPrincipal authenticatedActor
    ) {
        if (requestedRole == UserRole.DEVICE) {
            throw new InvalidUserManagementException("DEVICE is an internal identity role and cannot be assigned to user accounts");
        }

        // Serialize lifecycle changes in stable ID order. This keeps the last-enabled-ADMIN
        // check correct when two administrators attempt concurrent demotions or disablements.
        List<UserAccount> lockedAccounts = userRepository.findAllForAccountManagementUpdate();
        Map<Long, UserAccount> accountsById = new HashMap<>();
        for (UserAccount account : lockedAccounts) {
            accountsById.put(account.getId(), account);
        }

        UserAccount actor = accountsById.get(authenticatedActor.getId());
        if (actor == null || actor.getRole() != UserRole.ADMIN || !actor.isEnabled()) {
            throw new AccessDeniedException("An enabled ADMIN account is required");
        }

        UserAccount target = accountsById.get(targetUserId);
        if (target == null) {
            throw new UserNotFoundException();
        }
        if (target.getId().equals(actor.getId())) {
            throw new InvalidUserManagementException("Administrators cannot change their own role or enabled status");
        }

        boolean removesEnabledAdmin = target.getRole() == UserRole.ADMIN
                && target.isEnabled()
                && (requestedRole != UserRole.ADMIN || !requestedEnabled);
        long enabledAdminCount = lockedAccounts.stream()
                .filter(account -> account.isEnabled() && account.getRole() == UserRole.ADMIN)
                .count();
        if (removesEnabledAdmin && enabledAdminCount <= 1) {
            throw new InvalidUserManagementException("At least one enabled ADMIN account must remain");
        }

        UserRole previousRole = target.getRole();
        boolean previousEnabled = target.isEnabled();
        if (previousRole == requestedRole && previousEnabled == requestedEnabled) {
            return toAccountResponse(target);
        }

        target.applyAdministrativeAccess(requestedRole, requestedEnabled);
        // Pending second-factor challenges are not durable sessions and must not survive an
        // account lifecycle change, especially across disable/re-enable operations.
        mfaLoginChallengeRepository.deleteAllByUserId(target.getId());
        auditRepository.saveAndFlush(new UserAccountAudit(
                target,
                previousRole,
                previousEnabled,
                requestedRole,
                requestedEnabled,
                actor
        ));

        return toAccountResponse(target);
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<UserAccountAuditResponse> searchAudits(
            int page,
            int size,
            Instant from,
            Instant to,
            String targetUsername,
            String actorUsername
    ) {
        Specification<UserAccountAudit> specification = AuditQuerySupport
                .<UserAccountAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<UserAccountAudit>equalIgnoreCase("targetUsername", targetUsername))
                .and(AuditQuerySupport.<UserAccountAudit>equalIgnoreCase("actorUsername", actorUsername));
        Page<UserAccountAudit> audits = auditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(this::toAuditResponse));
    }

    private UserAccountResponse toAccountResponse(UserAccount account) {
        return new UserAccountResponse(
                account.getId(),
                account.getUsername(),
                account.getFullName(),
                account.getRole(),
                account.isEnabled(),
                account.isMfaEnabled(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    private UserAccountAuditResponse toAuditResponse(UserAccountAudit audit) {
        return new UserAccountAuditResponse(
                audit.getId(),
                audit.getOperation(),
                audit.getTargetUserId(),
                audit.getTargetUsername(),
                audit.getPreviousRole(),
                audit.getNewRole(),
                audit.isPreviousEnabled(),
                audit.isNewEnabled(),
                audit.getActorUserId(),
                audit.getActorUsername(),
                audit.getChangedAt()
        );
    }
}
