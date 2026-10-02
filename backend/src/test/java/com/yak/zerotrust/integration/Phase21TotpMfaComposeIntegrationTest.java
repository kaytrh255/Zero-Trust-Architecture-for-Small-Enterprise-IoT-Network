package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises TOTP enrollment, one-time recovery, token invalidation, access control, and append-only MFA history. */
@EnabledIfEnvironmentVariable(named = "PHASE21_INTEGRATION", matches = "true")
class Phase21TotpMfaComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE21_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void protectsPrivilegedLoginWithTotpRecoveryAndAppendOnlyAudit() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String adminUsername = environment("PHASE21_ADMIN_USERNAME", "admin").trim().toLowerCase(Locale.ROOT);
        String adminPassword = requiredEnvironment("PHASE21_ADMIN_PASSWORD", "ADMIN_PASSWORD");

        HttpResult initialLogin = request("POST", "/api/auth/login", null, credentials(adminUsername, adminPassword));
        assertStatus(initialLogin, 200);
        assertNoStore(initialLogin, "password login");
        assertThat(initialLogin.body().path("mfaRequired").asBoolean()).isFalse();
        assertThat(initialLogin.body().path("mfaEnrollmentRequired").asBoolean())
                .as("the Phase 21 optional-MFA run disables mandatory enrollment")
                .isFalse();
        String preMfaToken = initialLogin.body().path("accessToken").asText();
        assertThat(preMfaToken).isNotBlank();

        HttpResult initialStatus = request("GET", "/api/auth/mfa/status", preMfaToken, null);
        assertStatus(initialStatus, 200);
        assertThat(initialStatus.body().path("enabled").asBoolean()).isFalse();

        TemporaryTrigger rejectAuditInsert = installRejectingMfaAuditInsertTrigger(adminUsername);
        try {
            HttpResult failedSetup = request(
                    "POST",
                    "/api/auth/mfa/enrollment",
                    preMfaToken,
                    JSON.createObjectNode().put("password", adminPassword)
            );
            assertThat(failedSetup.status())
                    .as("MFA enrollment must roll back when its audit event cannot be inserted; body: %s", failedSetup.body())
                    .isIn(409, 500);
        } finally {
            dropTemporaryTrigger(rejectAuditInsert);
        }
        assertThat(queryDatabase(
                "SELECT mfa_pending_secret_ciphertext IS NULL FROM public.users WHERE username = '" + adminUsername + "'"
        )).isEqualTo("t");

        HttpResult started = request(
                "POST",
                "/api/auth/mfa/enrollment",
                preMfaToken,
                JSON.createObjectNode().put("password", adminPassword)
        );
        assertStatus(started, 200);
        assertNoStore(started, "MFA enrollment setup");
        String secret = started.body().path("secret").asText();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(started.body().path("otpauthUri").asText()).contains("otpauth://totp/").contains(secret);
        assertThat(started.body().path("expiresAt").asText()).isNotBlank();

        String pendingCiphertext = queryDatabase(
                "SELECT mfa_pending_secret_ciphertext FROM public.users WHERE username = '" + adminUsername + "'"
        );
        assertThat(pendingCiphertext).isNotBlank().isNotEqualTo(secret).doesNotContain(secret);

        Instant confirmationTime = Instant.now();
        long enrollmentCounter = confirmationTime.getEpochSecond() / MfaTotpService.PERIOD_SECONDS;
        String confirmationCode = totp.generateCode(secret, confirmationTime);
        HttpResult confirmed = request(
                "POST",
                "/api/auth/mfa/enrollment/confirm",
                preMfaToken,
                JSON.createObjectNode().put("code", confirmationCode)
        );
        assertStatus(confirmed, 200);
        assertNoStore(confirmed, "MFA enrollment confirmation");
        assertThat(confirmed.body().path("status").path("enabled").asBoolean()).isTrue();
        assertThat(confirmed.body().path("status").path("recoveryCodesRemaining").asLong()).isEqualTo(10);
        JsonNode recoveryCodeNodes = confirmed.body().path("recoveryCodes");
        assertThat(recoveryCodeNodes.size()).isEqualTo(10);
        Set<String> recoveryCodes = new HashSet<>();
        recoveryCodeNodes.forEach(value -> recoveryCodes.add(value.asText()));
        assertThat(recoveryCodes).hasSize(10);
        String recoveryCode = recoveryCodeNodes.get(0).asText();
        assertThat(recoveryCode).matches("[0-9A-F]{4}(-[0-9A-F]{4}){7}");

        String activeCiphertext = queryDatabase(
                "SELECT mfa_secret_ciphertext FROM public.users WHERE username = '" + adminUsername + "'"
        );
        assertThat(activeCiphertext).isNotBlank().isNotEqualTo(secret).doesNotContain(secret);
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + adminUsername + "')"
                        + " AND code_hash ~ '^[0-9a-f]{64}$'"
        )).isEqualTo("10");
        String storedHashes = queryDatabase(
                "SELECT string_agg(code_hash, ',') FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + adminUsername + "')"
        );
        assertThat(storedHashes).doesNotContain(recoveryCode.replace("-", ""));
        assertStatus(request("GET", "/api/auth/me", preMfaToken, null), 401);

        HttpResult challengeLogin = loginForMfa(adminUsername, adminPassword);
        String challengeToken = challengeLogin.body().path("mfaToken").asText();
        assertStatus(request("GET", "/api/auth/me", challengeToken, null), 401);
        HttpResult invalidCode = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", challengeToken).put("code", "not-a-code")
        );
        assertStatus(invalidCode, 401);
        assertNoStore(invalidCode, "rejected MFA verification");

        waitForCounterAfter(enrollmentCounter);
        Instant loginCodeTime = Instant.now();
        long loginCounter = loginCodeTime.getEpochSecond() / MfaTotpService.PERIOD_SECONDS;
        String loginCode = totp.generateCode(secret, loginCodeTime);
        HttpResult verifiedLogin = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", challengeToken).put("code", loginCode)
        );
        assertStatus(verifiedLogin, 200);
        assertNoStore(verifiedLogin, "successful MFA verification");
        assertThat(verifiedLogin.body().path("mfaRequired").asBoolean()).isFalse();
        String mfaToken = verifiedLogin.body().path("accessToken").asText();
        assertThat(mfaToken).isNotBlank();
        assertStatus(request("GET", "/api/auth/me", mfaToken, null), 200);

        HttpResult totpReplayChallenge = loginForMfa(adminUsername, adminPassword);
        HttpResult replayedTotp = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode()
                        .put("mfaToken", totpReplayChallenge.body().path("mfaToken").asText())
                        .put("code", loginCode)
        );
        assertStatus(replayedTotp, 401);

        HttpResult consumedChallenge = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", challengeToken).put("code", recoveryCode)
        );
        assertStatus(consumedChallenge, 401);
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + adminUsername + "') "
                        + "AND used_at IS NULL"
        )).isEqualTo("10");

        HttpResult recoveryChallenge = loginForMfa(adminUsername, adminPassword);
        HttpResult recoveryLogin = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode()
                        .put("mfaToken", recoveryChallenge.body().path("mfaToken").asText())
                        .put("code", recoveryCode)
        );
        assertStatus(recoveryLogin, 200);
        assertNoStore(recoveryLogin, "recovery-code login");
        String recoveryLoginToken = recoveryLogin.body().path("accessToken").asText();
        assertThat(request("GET", "/api/auth/mfa/status", recoveryLoginToken, null)
                .body().path("recoveryCodesRemaining").asLong()).isEqualTo(9);

        HttpResult replayChallenge = loginForMfa(adminUsername, adminPassword);
        HttpResult replayedRecovery = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode()
                        .put("mfaToken", replayChallenge.body().path("mfaToken").asText())
                        .put("code", recoveryCode)
        );
        assertStatus(replayedRecovery, 401);

        HttpResult adminHistory = request(
                "GET",
                "/api/auth/mfa/audits?operation=RECOVERY_CODE_USED&username=" + adminUsername,
                recoveryLoginToken,
                null
        );
        assertStatus(adminHistory, 200);
        assertThat(adminHistory.body().path("totalElements").asLong()).isEqualTo(1);
        assertThat(adminHistory.body().path("content").get(0).path("operation").asText())
                .isEqualTo("RECOVERY_CODE_USED");
        assertThat(adminHistory.body().toString()).doesNotContain(secret).doesNotContain(recoveryCode);

        String userUsername = "phase21-user-" + suffix;
        String userPassword = "User21!" + suffix + "Aa";
        register(userUsername, userPassword, "Phase 21 Standard User");
        String userToken = login(userUsername, userPassword);
        assertStatus(request("GET", "/api/auth/mfa/status", userToken, null), 403);
        assertStatus(request("GET", "/api/auth/mfa/audits", userToken, null), 403);

        String analystUsername = "phase21-analyst-" + suffix;
        String analystPassword = "Analyst21!" + suffix + "Aa";
        register(analystUsername, analystPassword, "Phase 21 Security Analyst");
        promoteToAnalyst(analystUsername);
        String analystToken = login(analystUsername, analystPassword);
        assertStatus(request("GET", "/api/auth/mfa/audits", analystToken, null), 200);
        assertStatus(request("POST", "/api/auth/mfa/enrollment", userToken,
                JSON.createObjectNode().put("password", userPassword)), 403);

        waitForCounterAfter(loginCounter);
        Instant disableTime = Instant.now();
        String disableCode = totp.generateCode(secret, disableTime);
        HttpResult disabled = request(
                "POST",
                "/api/auth/mfa/disable",
                recoveryLoginToken,
                JSON.createObjectNode().put("password", adminPassword).put("code", disableCode)
        );
        assertStatus(disabled, 200);
        assertNoStore(disabled, "MFA disable");
        assertThat(disabled.body().path("enabled").asBoolean()).isFalse();
        assertThat(disabled.body().path("recoveryCodesRemaining").asLong()).isZero();
        assertStatus(request("GET", "/api/auth/me", recoveryLoginToken, null), 401);

        HttpResult loginAfterDisable = request("POST", "/api/auth/login", null, credentials(adminUsername, adminPassword));
        assertStatus(loginAfterDisable, 200);
        assertThat(loginAfterDisable.body().path("mfaRequired").asBoolean()).isFalse();
        assertStatus(request("GET", "/api/auth/me", loginAfterDisable.body().path("accessToken").asText(), null), 200);

        String userIdSql = "(SELECT id FROM public.users WHERE username = '" + adminUsername + "')";
        assertThat(queryDatabase(
                "SELECT string_agg(operation, ',' ORDER BY id) FROM public.mfa_security_audits WHERE user_id = " + userIdSql
        )).isEqualTo("ENROLLMENT_STARTED,ENABLED,RECOVERY_CODE_USED,DISABLED");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'mfa_recovery_codes' "
                        + "AND column_name IN ('recovery_code', 'code', 'plaintext_code')"
        )).isEqualTo("0");
        assertMfaAuditHistoryIsAppendOnly(adminHistory.body().path("content").get(0).path("id").asLong(), userIdSql);
    }

    private HttpResult loginForMfa(String username, String password) throws Exception {
        HttpResult result = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(result, 200);
        assertNoStore(result, "MFA challenge login");
        assertThat(result.body().path("mfaRequired").asBoolean()).isTrue();
        assertThat(result.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(result.body().path("accessToken").isNull()).isTrue();
        assertThat(result.body().path("challengeExpiresInSeconds").asLong()).isEqualTo(300);
        assertThat(result.body().path("mfaToken").asText()).isNotBlank();
        return result;
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

    private TemporaryTrigger installRejectingMfaAuditInsertTrigger(String username) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase21_reject_" + suffix;
        String triggerName = "phase21_reject_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id = (SELECT id FROM public.users WHERE username = '" + username + "') "
                + "AND NEW.operation = 'ENROLLMENT_STARTED' THEN "
                + "RAISE EXCEPTION 'Phase 21 injected MFA audit failure' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public.mfa_security_audits "
                + "FOR EACH ROW EXECUTE FUNCTION public." + functionName + "();";
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_COMMAND, sql);
        assertThat(result.exitCode())
                .as("temporary MFA audit-failure trigger should install; output: %s", result.output())
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
                .as("temporary MFA audit-failure trigger should be removed; output: %s", result.output())
                .isZero();
    }

    private void assertMfaAuditHistoryIsAppendOnly(long auditId, String userIdSql) throws Exception {
        CommandResult update = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.mfa_security_audits SET username = username WHERE id = " + auditId
        );
        assertSqlRejected(update, "updating MFA security history");

        CommandResult delete = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DELETE FROM public.mfa_security_audits WHERE id = " + auditId
        );
        assertSqlRejected(delete, "deleting MFA security history");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_security_audits WHERE user_id = " + userIdSql
        )).isEqualTo("4");
    }

    private void assertSqlRejected(CommandResult result, String operation) {
        assertThat(result.exitCode())
                .as("database should reject %s; output: %s", operation, result.output())
                .isNotZero();
        assertThat(result.output().toLowerCase(Locale.ROOT))
                .as("database should reject %s using the append-only trigger", operation)
                .contains("audit history is append-only");
    }

    private void register(String username, String password, String fullName) throws Exception {
        HttpResult result = request(
                "POST",
                "/api/auth/register",
                null,
                JSON.createObjectNode().put("username", username).put("password", password).put("fullName", fullName)
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
                .as("registered analyst can be assigned the analyst role; output: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("UPDATE 1");
    }

    private String login(String username, String password) throws Exception {
        HttpResult result = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(result, 200);
        assertThat(result.body().path("mfaRequired").asBoolean()).isFalse();
        assertThat(result.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        String token = result.body().path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private ObjectNode credentials(String username, String password) {
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase21-mfa-test", sql);
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

    private static String requiredEnvironment(String primary, String fallback) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallback);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 21 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body, String cacheControl) {
    }

    private record TemporaryTrigger(String triggerName, String functionName) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
