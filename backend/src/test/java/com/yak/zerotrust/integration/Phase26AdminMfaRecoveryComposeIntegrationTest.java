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

/** Verifies two-person admin MFA recovery, atomic cleanup, token revocation, and actor-attributed audit. */
@EnabledIfEnvironmentVariable(named = "PHASE26_INTEGRATION", matches = "true")
class Phase26AdminMfaRecoveryComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE26_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void differentMfaEnabledAdminRecoversPrivilegedAccountsWithAtomicCleanupAndAudit() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String adminUsername = "phase26-admin-" + suffix;
        String analystUsername = "phase26-analyst-" + suffix;
        String secondAdminUsername = "phase26-target-admin-" + suffix;
        String adminPassword = "Admin26!" + suffix + "Aa";
        String analystPassword = "Analyst26!" + suffix + "Aa";
        String secondAdminPassword = "Target26!" + suffix + "Aa";

        register(adminUsername, adminPassword, "Phase 26 Recovery Administrator");
        register(analystUsername, analystPassword, "Phase 26 Recovery Analyst");
        register(secondAdminUsername, secondAdminPassword, "Phase 26 Target Administrator");
        promote(adminUsername, "ADMIN");
        promote(analystUsername, "SECURITY_ANALYST");
        promote(secondAdminUsername, "ADMIN");

        EnrolledAccount actingAdmin = enrollPrivilegedAccount(adminUsername, adminPassword);
        EnrolledAccount analyst = enrollPrivilegedAccount(analystUsername, analystPassword);
        EnrolledAccount secondAdmin = enrollPrivilegedAccount(secondAdminUsername, secondAdminPassword);

        HttpResult analystLoginChallenge = loginForMfaChallenge(analystUsername, analystPassword);
        HttpResult secondAdminLoginChallenge = loginForMfaChallenge(secondAdminUsername, secondAdminPassword);
        assertThat(analystLoginChallenge.body().path("mfaToken").asText()).isNotBlank();
        assertThat(secondAdminLoginChallenge.body().path("mfaToken").asText()).isNotBlank();

        String adminId = userIdSql(adminUsername);
        String adminIdValue = queryDatabase("SELECT id FROM public.users WHERE username = '" + adminUsername + "'");
        String analystId = userIdSql(analystUsername);
        String analystIdValue = queryDatabase("SELECT id FROM public.users WHERE username = '" + analystUsername + "'");
        String secondAdminId = userIdSql(secondAdminUsername);
        assertThat(queryDatabase("SELECT mfa_enabled FROM public.users WHERE id = " + analystId)).isEqualTo("t");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + analystId))
                .isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + analystId
                        + " AND consumed_at IS NULL"
        )).isEqualTo("1");
        String analystVersionBefore = queryDatabase(
                "SELECT mfa_auth_version FROM public.users WHERE id = " + analystId
        );
        String secondAdminVersionBefore = queryDatabase(
                "SELECT mfa_auth_version FROM public.users WHERE id = " + secondAdminId
        );

        HttpResult selfRecovery = recover(
                actingAdmin.accessToken(), adminUsername, adminPassword, freshCode(adminUsername, actingAdmin.secret())
        );
        assertStatus(selfRecovery, 400);
        assertNoStore(selfRecovery, "rejected self-recovery");

        HttpResult wrongPassword = recover(
                actingAdmin.accessToken(), analystUsername, "Incorrect26!Password", freshCode(adminUsername, actingAdmin.secret())
        );
        assertStatus(wrongPassword, 400);
        assertNoStore(wrongPassword, "rejected admin password proof");

        HttpResult invalidCode = recover(
                actingAdmin.accessToken(), analystUsername, adminPassword, guaranteedInvalidCode(actingAdmin.secret())
        );
        assertStatus(invalidCode, 400);
        assertNoStore(invalidCode, "rejected admin TOTP proof");
        assertThat(queryDatabase("SELECT mfa_enabled FROM public.users WHERE id = " + analystId)).isEqualTo("t");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + analystId))
                .isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + analystId
                        + " AND consumed_at IS NULL"
        )).isEqualTo("1");
        assertStatus(request("GET", "/api/auth/me", analyst.accessToken(), null), 200);

        String actorCounterBeforeAuditFailure = queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE id = " + adminId
        );
        TemporaryTrigger rejectAudit = installRejectingAdminRecoveryAuditTrigger(analystUsername);
        try {
            HttpResult failedAuditInsert = recover(
                    actingAdmin.accessToken(), analystUsername, adminPassword,
                    freshCode(adminUsername, actingAdmin.secret())
            );
            assertThat(failedAuditInsert.status())
                    .as("MFA recovery must roll back if the audit event cannot be inserted; body: %s", failedAuditInsert.body())
                    .isIn(409, 500);
            assertNoStore(failedAuditInsert, "recovery rolled back after audit failure");
        } finally {
            dropTemporaryTrigger(rejectAudit);
        }
        assertThat(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE id = " + adminId
        )).isEqualTo(actorCounterBeforeAuditFailure);
        assertThat(queryDatabase("SELECT mfa_enabled FROM public.users WHERE id = " + analystId)).isEqualTo("t");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + analystId))
                .isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + analystId
                        + " AND consumed_at IS NULL"
        )).isEqualTo("1");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_security_audits WHERE user_id = " + analystId
                        + " AND operation = 'ADMIN_MFA_RECOVERY'"
        )).isEqualTo("0");
        assertStatus(request("GET", "/api/auth/me", analyst.accessToken(), null), 200);

        String analystRecoveryCode = freshCode(adminUsername, actingAdmin.secret());
        HttpResult recoveredAnalyst = recover(
                actingAdmin.accessToken(), analystUsername, adminPassword, analystRecoveryCode
        );
        assertStatus(recoveredAnalyst, 200);
        assertNoStore(recoveredAnalyst, "security-analyst MFA recovery");
        assertThat(recoveredAnalyst.body().path("enabled").asBoolean()).isFalse();
        assertThat(recoveredAnalyst.body().path("recoveryCodesRemaining").asLong()).isZero();
        assertThat(recoveredAnalyst.body().has("secret")).isFalse();
        assertThat(recoveredAnalyst.body().has("recoveryCodes")).isFalse();
        assertThat(queryDatabase("SELECT mfa_enabled FROM public.users WHERE id = " + analystId)).isEqualTo("f");
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + analystId))
                .isEqualTo(Integer.toString(Integer.parseInt(analystVersionBefore) + 1));
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + analystId))
                .isEqualTo("0");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + analystId))
                .isEqualTo("0");
        assertStatus(request("GET", "/api/auth/me", analyst.accessToken(), null), 401);

        HttpResult replayedAdminCode = recover(
                actingAdmin.accessToken(), secondAdminUsername, adminPassword, analystRecoveryCode
        );
        assertStatus(replayedAdminCode, 400);
        assertNoStore(replayedAdminCode, "replayed administrator TOTP code");
        assertThat(queryDatabase("SELECT mfa_enabled FROM public.users WHERE id = " + secondAdminId)).isEqualTo("t");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + secondAdminId))
                .isEqualTo("10");
        assertStatus(request("GET", "/api/auth/me", secondAdmin.accessToken(), null), 200);

        HttpResult recoveredAdmin = recover(
                actingAdmin.accessToken(), secondAdminUsername, adminPassword,
                freshCode(adminUsername, actingAdmin.secret())
        );
        assertStatus(recoveredAdmin, 200);
        assertNoStore(recoveredAdmin, "administrator-target MFA recovery");
        assertThat(recoveredAdmin.body().path("enabled").asBoolean()).isFalse();
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + secondAdminId))
                .isEqualTo(Integer.toString(Integer.parseInt(secondAdminVersionBefore) + 1));
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + secondAdminId))
                .isEqualTo("0");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + secondAdminId))
                .isEqualTo("0");
        assertStatus(request("GET", "/api/auth/me", secondAdmin.accessToken(), null), 401);
        assertStatus(request("GET", "/api/auth/me", actingAdmin.accessToken(), null), 200);

        assertThat(queryDatabase(
                "SELECT actor_username FROM public.mfa_security_audits WHERE user_id = " + analystId
                        + " AND operation = 'ADMIN_MFA_RECOVERY'"
        )).isEqualTo(adminUsername);
        assertThat(queryDatabase(
                "SELECT actor_user_id = " + adminId + " FROM public.mfa_security_audits WHERE user_id = " + analystId
                        + " AND operation = 'ADMIN_MFA_RECOVERY'"
        )).isEqualTo("t");
        assertThat(queryDatabase(
                "SELECT actor_username FROM public.mfa_security_audits WHERE user_id = " + secondAdminId
                        + " AND operation = 'ADMIN_MFA_RECOVERY'"
        )).isEqualTo(adminUsername);

        HttpResult audit = request(
                "GET",
                "/api/auth/mfa/audits?operation=ADMIN_MFA_RECOVERY&username=" + analystUsername,
                actingAdmin.accessToken(),
                null
        );
        assertStatus(audit, 200);
        assertNoStore(audit, "admin MFA audit history");
        JsonNode auditEvent = audit.body().path("content").get(0);
        assertThat(audit.body().path("totalElements").asLong()).isEqualTo(1);
        assertThat(auditEvent.path("operation").asText()).isEqualTo("ADMIN_MFA_RECOVERY");
        assertThat(auditEvent.path("userId").asLong()).isEqualTo(Long.parseLong(analystIdValue));
        assertThat(auditEvent.path("username").asText()).isEqualTo(analystUsername);
        assertThat(auditEvent.path("actorUserId").asLong()).isEqualTo(Long.parseLong(adminIdValue));
        assertThat(auditEvent.path("actorUsername").asText()).isEqualTo(adminUsername);
        assertThat(auditEvent.toString()).doesNotContain(actingAdmin.secret(), adminPassword, analystRecoveryCode);

        HttpResult nextAnalystLogin = request("POST", "/api/auth/login", null, credentials(analystUsername, analystPassword));
        assertStatus(nextAnalystLogin, 200);
        assertNoStore(nextAnalystLogin, "post-recovery required enrollment challenge");
        assertThat(nextAnalystLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(nextAnalystLogin.body().path("accessToken").isNull()).isTrue();

        HttpResult nextAdminLogin = request(
                "POST", "/api/auth/login", null, credentials(secondAdminUsername, secondAdminPassword)
        );
        assertStatus(nextAdminLogin, 200);
        assertThat(nextAdminLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(nextAdminLogin.body().path("accessToken").isNull()).isTrue();
    }

    private EnrolledAccount enrollPrivilegedAccount(String username, String password) throws Exception {
        HttpResult login = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(login, 200);
        assertNoStore(login, "required enrollment challenge");
        assertThat(login.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        String enrollmentToken = login.body().path("enrollmentToken").asText();

        HttpResult setup = request(
                "POST",
                "/api/auth/mfa/required-enrollment",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken)
        );
        assertStatus(setup, 200);
        assertNoStore(setup, "required enrollment setup");
        String secret = setup.body().path("secret").asText();
        HttpResult completed = request(
                "POST",
                "/api/auth/mfa/required-enrollment/confirm",
                null,
                JSON.createObjectNode()
                        .put("enrollmentToken", enrollmentToken)
                        .put("code", totp.generateCode(secret, Instant.now()))
        );
        assertStatus(completed, 200);
        assertNoStore(completed, "required enrollment completion");
        assertThat(completed.body().path("recoveryCodes").size()).isEqualTo(10);
        String accessToken = completed.body().path("session").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return new EnrolledAccount(accessToken, secret);
    }

    private HttpResult loginForMfaChallenge(String username, String password) throws Exception {
        HttpResult result = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(result, 200);
        assertNoStore(result, "MFA login challenge");
        assertThat(result.body().path("mfaRequired").asBoolean()).isTrue();
        return result;
    }

    private HttpResult recover(String actorToken, String targetUsername, String password, String code) throws Exception {
        return request(
                "POST",
                "/api/admin/mfa/recovery",
                actorToken,
                JSON.createObjectNode()
                        .put("targetUsername", targetUsername)
                        .put("password", password)
                        .put("code", code)
        );
    }

    private TemporaryTrigger installRejectingAdminRecoveryAuditTrigger(String username) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase26_reject_" + suffix;
        String triggerName = "phase26_reject_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id = (SELECT id FROM public.users WHERE username = '" + username + "') "
                + "AND NEW.operation = 'ADMIN_MFA_RECOVERY' THEN "
                + "RAISE EXCEPTION 'Phase 26 injected MFA audit failure' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public.mfa_security_audits "
                + "FOR EACH ROW EXECUTE FUNCTION public." + functionName + "();";
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_COMMAND, sql);
        assertThat(result.exitCode())
                .as("temporary admin-recovery audit-failure trigger should install; output: %s", result.output())
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
                .as("temporary admin-recovery audit-failure trigger should be removed; output: %s", result.output())
                .isZero();
    }

    private String freshCode(String username, String secret) throws Exception {
        long lastAcceptedCounter = Long.parseLong(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        ));
        Instant now = Instant.now();
        while (Math.floorDiv(now.getEpochSecond(), MfaTotpService.PERIOD_SECONDS) <= lastAcceptedCounter) {
            long nextCounterStartMillis = (lastAcceptedCounter + 1) * MfaTotpService.PERIOD_SECONDS * 1_000L;
            long waitMillis = Math.max(50L, nextCounterStartMillis - now.toEpochMilli() + 1_000L);
            Thread.sleep(waitMillis);
            now = Instant.now();
        }
        return totp.generateCode(secret, now);
    }

    private String guaranteedInvalidCode(String secret) {
        long counter = Math.floorDiv(Instant.now().getEpochSecond(), MfaTotpService.PERIOD_SECONDS);
        Set<String> validCodes = new HashSet<>();
        for (long candidateCounter = Math.max(0, counter - 1); candidateCounter <= counter + 1; candidateCounter++) {
            validCodes.add(totp.generateCode(secret, Instant.ofEpochSecond(
                    candidateCounter * MfaTotpService.PERIOD_SECONDS
            )));
        }
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String code = String.format(Locale.ROOT, "%06d", candidate);
            if (!validCodes.contains(code)) return code;
        }
        throw new IllegalStateException("Could not construct an invalid TOTP code");
    }

    private void register(String username, String password, String fullName) throws Exception {
        HttpResult result = request(
                "POST",
                "/api/auth/register",
                null,
                JSON.createObjectNode()
                        .put("username", username)
                        .put("password", password)
                        .put("fullName", fullName)
        );
        assertStatus(result, 201);
        assertThat(result.body().path("role").asText()).isEqualTo("USER");
    }

    private void promote(String username, String role) throws Exception {
        CommandResult result = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.users SET role = '" + role + "' WHERE username = '" + username + "'"
        );
        assertThat(result.exitCode())
                .as("registered user can be assigned role %s; output: %s", role, result.output())
                .isZero();
        assertThat(result.output()).contains("UPDATE 1");
    }

    private ObjectNode credentials(String username, String password) {
        return JSON.createObjectNode().put("username", username).put("password", password);
    }

    private String userIdSql(String username) throws Exception {
        return "(SELECT id FROM public.users WHERE username = '" + username + "')";
    }

    private HttpResult request(String method, String path, String token, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase26-mfa-test", sql);
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

    private record EnrolledAccount(String accessToken, String secret) {
    }

    private record TemporaryTrigger(String triggerName, String functionName) {
    }

    private record HttpResult(int status, JsonNode body, String cacheControl) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
