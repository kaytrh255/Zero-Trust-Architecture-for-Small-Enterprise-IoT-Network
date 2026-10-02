package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuthResponse;
import com.yak.zerotrust.dto.MfaEnrollmentResponse;
import com.yak.zerotrust.dto.MfaRecoveryCodesResponse;
import com.yak.zerotrust.dto.MfaRequiredEnrollmentCompletionResponse;
import com.yak.zerotrust.dto.MfaStatusResponse;
import com.yak.zerotrust.dto.UserResponse;
import com.yak.zerotrust.entity.MfaLoginChallenge;
import com.yak.zerotrust.entity.MfaLoginChallengePurpose;
import com.yak.zerotrust.entity.MfaRecoveryCode;
import com.yak.zerotrust.entity.MfaSecurityAuditOperation;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.InvalidMfaOperationException;
import com.yak.zerotrust.repository.MfaLoginChallengeRepository;
import com.yak.zerotrust.repository.MfaRecoveryCodeRepository;
import com.yak.zerotrust.repository.UserRepository;
import com.yak.zerotrust.security.JwtService;
import com.yak.zerotrust.security.MfaChallengeClaims;
import com.yak.zerotrust.security.MfaLoginVerification;
import com.yak.zerotrust.security.MfaSecretCrypto;
import com.yak.zerotrust.security.MfaTotpService;
import com.yak.zerotrust.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.jsonwebtoken.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@Service
public class MfaService {

    public static final long ENROLLMENT_EXPIRATION_SECONDS = JwtService.MFA_ENROLLMENT_CHALLENGE_EXPIRATION_SECONDS;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_RANDOM_BYTES = 16;
    private static final SecureRandom RECOVERY_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MfaLoginChallengeRepository challengeRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaSecretCrypto secretCrypto;
    private final MfaTotpService totpService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationAuditService authenticationAuditService;
    private final MfaSecurityAuditService mfaSecurityAuditService;

    public MfaService(
            UserRepository userRepository,
            MfaLoginChallengeRepository challengeRepository,
            MfaRecoveryCodeRepository recoveryCodeRepository,
            MfaSecretCrypto secretCrypto,
            MfaTotpService totpService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationAuditService authenticationAuditService,
            MfaSecurityAuditService mfaSecurityAuditService
    ) {
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.secretCrypto = secretCrypto;
        this.totpService = totpService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationAuditService = authenticationAuditService;
        this.mfaSecurityAuditService = mfaSecurityAuditService;
    }

    @Transactional
    public String issueLoginChallenge(UserPrincipal principal) {
        UserAccount user = userRepository.findByIdForMfaUpdate(principal.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!user.isEnabled() || !user.isMfaEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        Instant now = Instant.now();
        challengeRepository.deleteExpiredBefore(now);
        String challengeId = UUID.randomUUID().toString();
        challengeRepository.saveAndFlush(new MfaLoginChallenge(
                challengeId,
                user.getId(),
                user.getUsername(),
                MfaLoginChallengePurpose.LOGIN,
                now.plusSeconds(JwtService.MFA_CHALLENGE_EXPIRATION_SECONDS)
        ));
        return jwtService.generateMfaChallengeToken(user.getUsername(), challengeId);
    }

    @Transactional
    public String issueRequiredEnrollmentChallenge(UserPrincipal principal) {
        UserAccount user = userRepository.findByIdForMfaUpdate(principal.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!user.isEnabled() || user.isMfaEnabled() || !isPrivilegedRole(user.getRole())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        Instant now = Instant.now();
        challengeRepository.deleteExpiredBefore(now);
        String challengeId = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(jwtService.getMfaEnrollmentChallengeExpirationSeconds());
        challengeRepository.saveAndFlush(new MfaLoginChallenge(
                challengeId,
                user.getId(),
                user.getUsername(),
                MfaLoginChallengePurpose.ENROLLMENT,
                expiresAt
        ));
        return jwtService.generateMfaEnrollmentToken(user.getUsername(), challengeId);
    }

    @Transactional
    public MfaLoginVerification verifyLoginChallenge(String challengeToken, String code) {
        MfaChallengeClaims claims;
        try {
            claims = jwtService.extractMfaChallenge(challengeToken);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid MFA challenge", exception);
        }

        UserAccount user = userRepository.findByUsernameForMfaUpdate(claims.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA challenge"));
        MfaLoginChallenge challenge = challengeRepository.findByIdForUpdate(claims.challengeId())
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA challenge"));
        Instant now = Instant.now();
        if (challenge.getPurpose() != MfaLoginChallengePurpose.LOGIN
                || !challenge.getUsername().equals(user.getUsername())
                || !challenge.getUserId().equals(user.getId())
                || !challenge.isUsableAt(now)) {
            throw new BadCredentialsException("Invalid MFA challenge");
        }

        if (!user.isEnabled() || !user.isMfaEnabled() || user.getMfaSecretCiphertext() == null) {
            authenticationAuditService.recordFailedLogin(user.getUsername());
            return MfaLoginVerification.rejected(user.getUsername());
        }

        String supplied = code == null ? "" : code.trim();
        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(user.getMfaSecretCiphertext()),
                supplied,
                now,
                user.getMfaLastTotpCounter()
        );
        boolean usedRecoveryCode = false;
        boolean accepted = matchedCounter.isPresent();
        if (accepted) {
            user.acceptMfaTotpCounter(matchedCounter.getAsLong());
        } else {
            String recoveryCode = normalizeRecoveryCode(supplied);
            if (recoveryCode != null) {
                String candidateHash = sha256(recoveryCode);
                for (MfaRecoveryCode storedCode : recoveryCodeRepository.findUnusedByUserIdForUpdate(user.getId())) {
                    if (MessageDigest.isEqual(
                            candidateHash.getBytes(StandardCharsets.US_ASCII),
                            storedCode.getCodeHash().getBytes(StandardCharsets.US_ASCII)
                    )) {
                        storedCode.markUsed(now);
                        accepted = true;
                        usedRecoveryCode = true;
                        break;
                    }
                }
            }
        }

        if (!accepted) {
            authenticationAuditService.recordFailedLogin(user.getUsername());
            return MfaLoginVerification.rejected(user.getUsername());
        }

        challenge.consume(now);
        UserPrincipal principal = new UserPrincipal(user);
        if (usedRecoveryCode) {
            mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.RECOVERY_CODE_USED);
        }
        authenticationAuditService.recordSuccessfulLogin(principal);
        return MfaLoginVerification.accepted(principal);
    }

    @Transactional
    public MfaEnrollmentResponse beginEnrollment(UserPrincipal principal, String password) {
        UserAccount user = lockedPrivilegedAccount(principal);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidMfaOperationException("Password verification failed");
        }
        if (user.isMfaEnabled()) {
            throw new InvalidMfaOperationException("MFA is already enabled");
        }
        return startEnrollment(user, Instant.now().plusSeconds(ENROLLMENT_EXPIRATION_SECONDS));
    }

    @Transactional
    public MfaEnrollmentResponse beginRequiredEnrollment(String enrollmentToken) {
        RequiredEnrollmentContext context = requiredEnrollmentContext(enrollmentToken);
        return startEnrollment(context.user(), context.challenge().getExpiresAt());
    }

    @Transactional
    public Optional<MfaRequiredEnrollmentCompletionResponse> confirmRequiredEnrollment(String enrollmentToken, String code) {
        RequiredEnrollmentContext context = requiredEnrollmentContext(enrollmentToken);
        UserAccount user = context.user();
        Instant now = Instant.now();
        Instant expiresAt = user.getMfaEnrollmentExpiresAt();
        if (user.getMfaPendingSecretCiphertext() == null || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new InvalidMfaOperationException("MFA enrollment is missing or has expired; start again");
        }

        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(user.getMfaPendingSecretCiphertext()),
                code == null ? "" : code.trim(),
                now,
                -1
        );
        if (matchedCounter.isEmpty()) {
            authenticationAuditService.recordFailedLogin(user.getUsername());
            return Optional.empty();
        }

        user.confirmMfaEnrollment(matchedCounter.getAsLong());
        context.challenge().consume(now);
        List<String> recoveryCodes = replaceRecoveryCodes(user);
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.ENABLED);

        UserPrincipal principal = new UserPrincipal(user);
        authenticationAuditService.recordSuccessfulLogin(principal);
        String accessToken = jwtService.generateToken(user.getUsername(), user.getMfaAuthVersion());
        AuthResponse session = AuthResponse.authenticated(
                accessToken,
                jwtService.getExpirationSeconds(),
                new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.isEnabled())
        );
        return Optional.of(new MfaRequiredEnrollmentCompletionResponse(session, recoveryCodes));
    }

    @Transactional
    public MfaRecoveryCodesResponse confirmEnrollment(UserPrincipal principal, String code) {
        UserAccount user = lockedPrivilegedAccount(principal);
        Instant now = Instant.now();
        Instant expiresAt = user.getMfaEnrollmentExpiresAt();
        if (user.isMfaEnabled() || user.getMfaPendingSecretCiphertext() == null
                || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new InvalidMfaOperationException("MFA enrollment is missing or has expired; start again");
        }
        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(user.getMfaPendingSecretCiphertext()),
                code == null ? "" : code.trim(),
                now,
                -1
        );
        if (matchedCounter.isEmpty()) {
            throw new InvalidMfaOperationException("Authenticator code is invalid or expired");
        }

        user.confirmMfaEnrollment(matchedCounter.getAsLong());
        List<String> recoveryCodes = replaceRecoveryCodes(user);
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.ENABLED);
        return new MfaRecoveryCodesResponse(recoveryCodes, statusFor(user));
    }

    @Transactional
    public MfaStatusResponse status(UserPrincipal principal) {
        UserAccount user = lockedPrivilegedAccount(principal);
        return statusFor(user);
    }

    @Transactional
    public MfaStatusResponse disable(UserPrincipal principal, String password, String code) {
        UserAccount user = lockedPrivilegedAccount(principal);
        if (!user.isMfaEnabled() || user.getMfaSecretCiphertext() == null) {
            throw new InvalidMfaOperationException("MFA is not enabled");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidMfaOperationException("Password verification failed");
        }
        Instant now = Instant.now();
        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(user.getMfaSecretCiphertext()),
                code == null ? "" : code.trim(),
                now,
                user.getMfaLastTotpCounter()
        );
        if (matchedCounter.isEmpty()) {
            throw new InvalidMfaOperationException("Authenticator code is invalid or has already been used");
        }
        user.disableMfa();
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.DISABLED);
        return statusFor(user);
    }

    @Transactional
    public MfaStatusResponse disableWithRecoveryCode(
            UserPrincipal principal,
            String password,
            String recoveryCodeInput
    ) {
        UserAccount user = lockedPrivilegedAccount(principal);
        if (!user.isMfaEnabled() || user.getMfaSecretCiphertext() == null) {
            throw new InvalidMfaOperationException("MFA is not enabled");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidMfaOperationException("Password verification failed");
        }

        String recoveryCode = normalizeRecoveryCode(recoveryCodeInput == null ? "" : recoveryCodeInput.trim());
        if (recoveryCode == null) {
            throw new InvalidMfaOperationException("Recovery code is invalid or has already been used");
        }
        String candidateHash = sha256(recoveryCode);
        MfaRecoveryCode matchedCode = null;
        for (MfaRecoveryCode storedCode : recoveryCodeRepository.findUnusedByUserIdForUpdate(user.getId())) {
            if (MessageDigest.isEqual(
                    candidateHash.getBytes(StandardCharsets.US_ASCII),
                    storedCode.getCodeHash().getBytes(StandardCharsets.US_ASCII)
            )) {
                matchedCode = storedCode;
                break;
            }
        }
        if (matchedCode == null) {
            throw new InvalidMfaOperationException("Recovery code is invalid or has already been used");
        }

        Instant now = Instant.now();
        matchedCode.markUsed(now);
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.RECOVERY_CODE_USED);
        user.disableMfa();
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.DISABLED);
        return statusFor(user);
    }

    @Transactional
    public MfaStatusResponse recoverMfaForAdmin(
            UserPrincipal actorPrincipal,
            String targetUsername,
            String password,
            String code
    ) {
        String normalizedTargetUsername = targetUsername == null ? "" : targetUsername.trim();
        Long targetId = userRepository.findIdByUsername(normalizedTargetUsername)
                .orElseThrow(() -> new InvalidMfaOperationException("Target privileged account was not found"));
        Long actorId = actorPrincipal.getId();
        if (actorId.equals(targetId)) {
            throw new InvalidMfaOperationException("An administrator cannot recover their own MFA");
        }

        Long firstId = actorId.compareTo(targetId) < 0 ? actorId : targetId;
        Long secondId = actorId.compareTo(targetId) < 0 ? targetId : actorId;
        UserAccount first = userRepository.findByIdForMfaUpdate(firstId)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        UserAccount second = userRepository.findByIdForMfaUpdate(secondId)
                .orElseThrow(() -> new InvalidMfaOperationException("Target privileged account was not found"));
        UserAccount actor = actorId.equals(firstId) ? first : second;
        UserAccount target = targetId.equals(firstId) ? first : second;

        if (!actor.isEnabled() || actor.getRole() != UserRole.ADMIN
                || !actor.isMfaEnabled() || actor.getMfaSecretCiphertext() == null) {
            throw new AccessDeniedException("MFA recovery requires a different MFA-enabled administrator");
        }
        if (!target.isEnabled() || !isPrivilegedRole(target.getRole())
                || !target.isMfaEnabled() || target.getMfaSecretCiphertext() == null) {
            throw new InvalidMfaOperationException("Target must be an enabled privileged account with MFA enabled");
        }
        if (!passwordEncoder.matches(password, actor.getPasswordHash())) {
            throw new InvalidMfaOperationException("Password verification failed");
        }

        Instant now = Instant.now();
        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(actor.getMfaSecretCiphertext()),
                code == null ? "" : code.trim(),
                now,
                actor.getMfaLastTotpCounter()
        );
        if (matchedCounter.isEmpty()) {
            throw new InvalidMfaOperationException("Authenticator code is invalid or has already been used");
        }

        actor.acceptMfaTotpCounter(matchedCounter.getAsLong());
        target.disableMfa();
        recoveryCodeRepository.deleteAllByUserId(target.getId());
        challengeRepository.deleteAllByUserId(target.getId());
        mfaSecurityAuditService.record(target, MfaSecurityAuditOperation.ADMIN_MFA_RECOVERY, actor);
        return statusFor(target);
    }

    @Transactional
    public MfaRecoveryCodesResponse rotateRecoveryCodes(UserPrincipal principal, String password, String code) {
        UserAccount user = lockedPrivilegedAccount(principal);
        if (!user.isMfaEnabled() || user.getMfaSecretCiphertext() == null) {
            throw new InvalidMfaOperationException("MFA must be enabled to rotate recovery codes");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidMfaOperationException("Password verification failed");
        }

        Instant now = Instant.now();
        OptionalLong matchedCounter = totpService.findMatchingCounter(
                secretCrypto.decrypt(user.getMfaSecretCiphertext()),
                code == null ? "" : code.trim(),
                now,
                user.getMfaLastTotpCounter()
        );
        if (matchedCounter.isEmpty()) {
            throw new InvalidMfaOperationException("Authenticator code is invalid or has already been used");
        }

        user.acceptMfaTotpCounter(matchedCounter.getAsLong());
        List<String> recoveryCodes = replaceRecoveryCodes(user);
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.RECOVERY_CODES_ROTATED);
        return new MfaRecoveryCodesResponse(recoveryCodes, statusFor(user));
    }

    private MfaEnrollmentResponse startEnrollment(UserAccount user, Instant expiresAt) {
        String secret = totpService.generateSecret();
        user.beginMfaEnrollment(secretCrypto.encrypt(secret), expiresAt);
        mfaSecurityAuditService.record(user, MfaSecurityAuditOperation.ENROLLMENT_STARTED);
        return new MfaEnrollmentResponse(secret, totpService.provisioningUri(user.getUsername(), secret), expiresAt);
    }

    private RequiredEnrollmentContext requiredEnrollmentContext(String enrollmentToken) {
        MfaChallengeClaims claims;
        try {
            claims = jwtService.extractMfaEnrollmentChallenge(enrollmentToken);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid MFA enrollment challenge", exception);
        }

        UserAccount user = userRepository.findByUsernameForMfaUpdate(claims.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA enrollment challenge"));
        MfaLoginChallenge challenge = challengeRepository.findByIdForUpdate(claims.challengeId())
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA enrollment challenge"));
        Instant now = Instant.now();
        if (challenge.getPurpose() != MfaLoginChallengePurpose.ENROLLMENT
                || !challenge.getUsername().equals(user.getUsername())
                || !challenge.getUserId().equals(user.getId())
                || !challenge.isUsableAt(now)
                || !user.isEnabled()
                || user.isMfaEnabled()
                || !isPrivilegedRole(user.getRole())) {
            throw new BadCredentialsException("Invalid MFA enrollment challenge");
        }
        return new RequiredEnrollmentContext(user, challenge);
    }

    private List<String> replaceRecoveryCodes(UserAccount user) {
        List<String> recoveryCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<MfaRecoveryCode> storedCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            String raw = generateRecoveryCode();
            recoveryCodes.add(formatRecoveryCode(raw));
            storedCodes.add(new MfaRecoveryCode(user.getId(), sha256(raw)));
        }
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        recoveryCodeRepository.saveAll(storedCodes);
        return recoveryCodes;
    }

    private boolean isPrivilegedRole(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.SECURITY_ANALYST;
    }

    private UserAccount lockedPrivilegedAccount(UserPrincipal principal) {
        UserAccount user = userRepository.findByIdForMfaUpdate(principal.getId())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SECURITY_ANALYST) {
            throw new AccessDeniedException("MFA settings are limited to privileged accounts");
        }
        return user;
    }

    private MfaStatusResponse statusFor(UserAccount user) {
        Instant expiresAt = user.getMfaEnrollmentExpiresAt();
        boolean pending = !user.isMfaEnabled()
                && user.getMfaPendingSecretCiphertext() != null
                && expiresAt != null
                && expiresAt.isAfter(Instant.now());
        return new MfaStatusResponse(
                user.isMfaEnabled(),
                pending,
                pending ? expiresAt : null,
                recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId())
        );
    }

    private String generateRecoveryCode() {
        byte[] randomBytes = new byte[RECOVERY_CODE_RANDOM_BYTES];
        RECOVERY_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().withUpperCase().formatHex(randomBytes);
    }

    private String formatRecoveryCode(String raw) {
        return raw.replaceAll("(.{4})(?=.)", "$1-");
    }

    private String normalizeRecoveryCode(String value) {
        String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[0-9A-F]{32}") ? normalized : null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RequiredEnrollmentContext(UserAccount user, MfaLoginChallenge challenge) {
    }
}
