package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yak.zerotrust.security.MfaTotpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests replacement, invalidation, replay protection, and audit for MFA recovery codes. */
@EnabledIfEnvironmentVariable(named = "PHASE23_INTEGRATION", matches = "true")
class Phase23MfaRecoveryCodeRotationComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADMIN_PSQL_COMMAND =
            "printf '%s\\n' \"$1\" | psql -v ON_ERROR_STOP=1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";
    private static final String ADMIN_PSQL_QUERY_COMMAND =
            "printf '%s\\n' \"$1\" | psql -v ON_ERROR_STOP=1 -t -A "
                    + "-U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final MfaTotpService totp = new MfaTotpService();
    private final String baseUrl = environment("PHASE23_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void rotatesCodesAtomicallyAndInvalidatesEveryPreviousCode() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String username = "phase23-analyst-" + suffix;
        String password = "Analyst23!" + suffix + "Aa";
        register(username, password);
        promoteToAnalyst(username);

        HttpResult passwordLogin = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(passwordLogin, 200);
        assertNoStore(passwordLogin, "required enrollment challenge");
        assertThat(passwordLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(passwordLogin.body().path("accessToken").isNull()).isTrue();
        String enrollmentToken = passwordLogin.body().path("enrollmentToken").asText();

        HttpResult setup = request(
                "POST",
                "/api/auth/mfa/required-enrollment",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken)
        );
        assertStatus(setup, 200);
        String secret = setup.body().path("secret").asText();
        String enrollmentCode = totp.generateCode(secret, Instant.now());
        HttpResult completed = request(
                "POST",
                "/api/auth/mfa/required-enrollment/confirm",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken).put("code", enrollmentCode)
        );
        assertStatus(completed, 200);
        assertNoStore(completed, "required MFA enrollment completion");
        JsonNode originalCodeNodes = completed.body().path("recoveryCodes");
        assertThat(originalCodeNodes.size()).isEqualTo(10);
        Set<String> originalCodes = new HashSet<>();
        originalCodeNodes.forEach(value -> originalCodes.add(value.asText()));
        assertThat(originalCodes).hasSize(10);
        String oldRecoveryCode = originalCodeNodes.get(0).asText();
        String accessToken = completed.body().path("session").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        long lastAcceptedCounter = Long.parseLong(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        ));
        waitForCounterAfter(lastAcceptedCounter);
        Instant rotationTime = Instant.now();
        String rotationCode = totp.generateCode(secret, rotationTime);
        String activeHashesBeforeFailure = queryDatabase(
                "SELECT string_agg(code_hash, ',' ORDER BY code_hash) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') AND used_at IS NULL"
        );
        HttpResult invalidTotp = request(
                "POST",
                "/api/auth/mfa/recovery-codes/rotate",
                accessToken,
                JSON.createObjectNode().put("password", password).put("code", "not-a-code")
        );
        assertStatus(invalidTotp, 400);
        HttpResult wrongPassword = request(
                "POST",
                "/api/auth/mfa/recovery-codes/rotate",
                accessToken,
                JSON.createObjectNode().put("password", "WrongPassword23!").put("code", rotationCode)
        );
        assertStatus(wrongPassword, 400);
        assertThat(queryDatabase(
                "SELECT string_agg(code_hash, ',' ORDER BY code_hash) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') AND used_at IS NULL"
        )).isEqualTo(activeHashesBeforeFailure);
        assertThat(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        )).isEqualTo(Long.toString(lastAcceptedCounter));

        TemporaryTrigger rejectRotationAudit = installRejectingRotationAuditTrigger(username);
        try {
            HttpResult failedRotation = request(
                    "POST",
                    "/api/auth/mfa/recovery-codes/rotate",
                    accessToken,
                    JSON.createObjectNode().put("password", password).put("code", rotationCode)
            );
            assertThat(failedRotation.status())
                    .as("rotation must roll back when its audit event cannot be inserted; body: %s", failedRotation.body())
                    .isIn(409, 500);
        } finally {
            dropTemporaryTrigger(rejectRotationAudit);
        }
        assertThat(queryDatabase(
                "SELECT string_agg(code_hash, ',' ORDER BY code_hash) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') AND used_at IS NULL"
        )).isEqualTo(activeHashesBeforeFailure);
        assertThat(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        )).isEqualTo(Long.toString(lastAcceptedCounter));
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_security_audits WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND operation = 'RECOVERY_CODES_ROTATED'"
        )).isEqualTo("0");

        HttpResult rotated = request(
                "POST",
                "/api/auth/mfa/recovery-codes/rotate",
                accessToken,
                JSON.createObjectNode().put("password", password).put("code", rotationCode)
        );
        assertStatus(rotated, 200);
        assertNoStore(rotated, "recovery code rotation");
        assertThat(rotated.body().path("status").path("enabled").asBoolean()).isTrue();
        assertThat(rotated.body().path("status").path("recoveryCodesRemaining").asLong()).isEqualTo(10);
        JsonNode replacementNodes = rotated.body().path("recoveryCodes");
        assertThat(replacementNodes.size()).isEqualTo(10);
        Set<String> replacementCodes = new HashSet<>();
        replacementNodes.forEach(value -> replacementCodes.add(value.asText()));
        assertThat(replacementCodes).hasSize(10);
        assertThat(replacementCodes.stream().noneMatch(originalCodes::contains)).isTrue();
        String newRecoveryCode = replacementNodes.get(0).asText();

        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND used_at IS NULL AND code_hash ~ '^[0-9a-f]{64}$'"
        )).isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND code_hash IN (" + sqlHashList(replacementCodes) + ")"
        )).isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND code_hash IN (" + sqlHashList(originalCodes) + ")"
        )).isEqualTo("0");
        HttpResult rotationAudit = request(
                "GET",
                "/api/auth/mfa/audits?operation=RECOVERY_CODES_ROTATED&username=" + username,
                accessToken,
                null
        );
        assertStatus(rotationAudit, 200);
        assertThat(rotationAudit.body().path("totalElements").asLong()).isEqualTo(1);
        assertThat(rotationAudit.body().toString()).doesNotContain(secret).doesNotContain(oldRecoveryCode).doesNotContain(newRecoveryCode);

        HttpResult replayedRotationCode = request(
                "POST",
                "/api/auth/mfa/recovery-codes/rotate",
                accessToken,
                JSON.createObjectNode().put("password", password).put("code", rotationCode)
        );
        assertStatus(replayedRotationCode, 400);
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND used_at IS NULL"
        )).isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_security_audits WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + username + "') "
                        + "AND operation = 'RECOVERY_CODES_ROTATED'"
        )).isEqualTo("1");

        HttpResult secondLogin = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(secondLogin, 200);
        assertThat(secondLogin.body().path("mfaRequired").asBoolean()).isTrue();
        String loginChallenge = secondLogin.body().path("mfaToken").asText();
        HttpResult rejectedOldCode = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", loginChallenge).put("code", oldRecoveryCode)
        );
        assertStatus(rejectedOldCode, 401);

        HttpResult acceptedNewCode = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", loginChallenge).put("code", newRecoveryCode)
        );
        assertStatus(acceptedNewCode, 200);
        assertNoStore(acceptedNewCode, "replacement recovery-code login");
        String recoveredSessionToken = acceptedNewCode.body().path("accessToken").asText();
        assertThat(recoveredSessionToken).isNotBlank();
        assertStatus(request("GET", "/api/auth/me", recoveredSessionToken, null), 200);
        assertThat(request("GET", "/api/auth/mfa/status", recoveredSessionToken, null)
                .body().path("recoveryCodesRemaining").asLong()).isEqualTo(9);
    }

    private TemporaryTrigger installRejectingRotationAuditTrigger(String username) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase23_reject_" + suffix;
        String triggerName = "phase23_reject_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id = (SELECT id FROM public.users WHERE username = '" + username + "') "
                + "AND NEW.operation = 'RECOVERY_CODES_ROTATED' THEN "
                + "RAISE EXCEPTION 'Phase 23 injected MFA audit failure' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public.mfa_security_audits "
                + "FOR EACH ROW EXECUTE FUNCTION public." + functionName + "();";
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_COMMAND, sql);
        assertThat(result.exitCode())
                .as("temporary MFA rotation audit-failure trigger should install; output: %s", result.output())
                .isZero();
        return new TemporaryTrigger(triggerName, functionName);
    }

    private void dropTemporaryTrigger(TemporaryTrigger trigger) throws Exception {
        CommandResult result = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DROP TRIGGER IF EXISTS " + trigger.triggerName() + " ON public.mfa_security_audits; "
                        + "DROP FUNCTION IF EXISTS public." + trigger.functionName() + "();"
        );
        assertThat(result.exitCode())
                .as("temporary MFA rotation audit-failure trigger should be removed; output: %s", result.output())
                .isZero();
    }

    private String sqlHashList(Set<String> recoveryCodes) throws NoSuchAlgorithmException {
        StringBuilder values = new StringBuilder();
        for (String recoveryCode : recoveryCodes) {
            if (values.length() > 0) values.append(", ");
            values.append('\'').append(recoveryCodeHash(recoveryCode)).append('\'');
        }
        return values.toString();
    }

    private String recoveryCodeHash(String recoveryCode) throws NoSuchAlgorithmException {
        byte[] normalized = recoveryCode.replace("-", "").getBytes(StandardCharsets.US_ASCII);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
    }

    private void waitForCounterAfter(long counter) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (Instant.now().getEpochSecond() / MfaTotpService.PERIOD_SECONDS <= counter) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for a fresh TOTP time step");
            }
            Thread.sleep(150);
        }
    }

    private void register(String username, String password) throws Exception {
        HttpResult result = request(
                "POST",
                "/api/auth/register",
                null,
                JSON.createObjectNode().put("username", username).put("password", password)
                        .put("fullName", "Phase 23 Recovery Rotation Analyst")
        );
        assertStatus(result, 201);
        assertThat(result.body().path("role").asText()).isEqualTo("USER");
    }

    private void promoteToAnalyst(String username) throws Exception {
        CommandResult result = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.users SET role = 'SECURITY_ANALYST' WHERE username = '" + username + "'"
        );
        assertThat(result.exitCode())
                .as("registered user can be assigned the analyst role; output: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("UPDATE 1");
    }

    private JsonNode credentials(String username, String password) {
        return JSON.createObjectNode().put("username", username).put("password", password);
    }

    private HttpResult request(String method, String path, String token, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode responseBody;
        if (response.body() == null || response.body().isBlank()) {
            responseBody = JSON.getNodeFactory().nullNode();
        } else {
            try {
                responseBody = JSON.readTree(response.body());
            } catch (Exception ignored) {
                responseBody = JSON.getNodeFactory().textNode(response.body());
            }
        }
        return new HttpResult(
                response.statusCode(),
                responseBody,
                response.headers().firstValue("Cache-Control").orElse("")
        );
    }

    private void assertNoStore(HttpResult result, String operation) {
        assertThat(result.cacheControl().toLowerCase(Locale.ROOT))
                .as("%s response should not be cached", operation)
                .contains("no-store");
    }

    private String queryDatabase(String sql) throws Exception {
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_QUERY_COMMAND, sql);
        assertThat(result.exitCode())
                .as("database query should succeed; output: %s", result.output())
                .isZero();
        return result.output().trim();
    }

    private CommandResult runDatabaseCommand(String command, String sql) throws Exception {
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase23-mfa-test", sql);
    }

    private CommandResult runCompose(String... arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command("docker", "compose");
        builder.command().addAll(java.util.List.of(arguments));
        builder.directory(repositoryRoot().toFile()).redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out waiting for Docker Compose PostgreSQL command");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private Path repositoryRoot() throws IOException {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("docker-compose.yml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("Could not locate docker-compose.yml from the test working directory");
        }
        return candidate;
    }

    private static void assertStatus(HttpResult result, int expectedStatus) {
        assertThat(result.status()).as("HTTP status; body: %s", result.body()).isEqualTo(expectedStatus);
    }

    private static String environment(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record HttpResult(int status, JsonNode body, String cacheControl) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record TemporaryTrigger(String triggerName, String functionName) {
    }
}
