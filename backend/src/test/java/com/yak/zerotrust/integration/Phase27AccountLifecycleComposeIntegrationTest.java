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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies ADMIN-managed roles/status, session revocation, and append-only actor/target history. */
@EnabledIfEnvironmentVariable(named = "PHASE27_INTEGRATION", matches = "true")
class Phase27AccountLifecycleComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE27_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void adminCanSafelyChangeAccountAccessAndEveryActualChangeIsAudited() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String adminUsername = "phase27-admin-" + suffix;
        String targetUsername = "phase27-target-" + suffix;
        String readerUsername = "phase27-reader-" + suffix;
        String adminPassword = "Admin27!" + suffix + "Aa";
        String targetPassword = "Target27!" + suffix + "Aa";
        String readerPassword = "Reader27!" + suffix + "Aa";

        register(adminUsername, adminPassword, "Phase 27 Account Administrator");
        register(targetUsername, targetPassword, "Phase 27 Managed Account");
        register(readerUsername, readerPassword, "Phase 27 Read Only Account");
        promote(adminUsername, "ADMIN");
        promote(targetUsername, "SECURITY_ANALYST");

        EnrolledAccount admin = enrollPrivilegedAccount(adminUsername, adminPassword);
        EnrolledAccount target = enrollPrivilegedAccount(targetUsername, targetPassword);
        HttpResult readerLogin = login(readerUsername, readerPassword);
        assertThat(readerLogin.body().path("mfaRequired").asBoolean()).isFalse();
        String targetToken = target.accessToken();
        String readerToken = readerLogin.body().path("accessToken").asText();
        String adminId = queryDatabase("SELECT id FROM public.users WHERE username = '" + adminUsername + "'");
        String targetId = queryDatabase("SELECT id FROM public.users WHERE username = '" + targetUsername + "'");

        HttpResult accountList = request("GET", "/api/admin/users", admin.accessToken(), null);
        assertStatus(accountList, 200);
        assertNoStore(accountList, "administrator account directory");
        assertThat(accountList.body().isArray()).isTrue();
        JsonNode listedTarget = findByUsername(accountList.body(), targetUsername);
        assertThat(listedTarget.path("role").asText()).isEqualTo("SECURITY_ANALYST");
        assertThat(listedTarget.path("enabled").asBoolean()).isTrue();
        assertThat(listedTarget.path("mfaEnabled").asBoolean()).isTrue();
        assertThat(listedTarget.has("passwordHash")).isFalse();
        assertThat(listedTarget.has("mfaSecretCiphertext")).isFalse();
        assertThat(listedTarget.has("mfaPendingSecretCiphertext")).isFalse();
        assertThat(accountList.body().toString()).doesNotContain(adminPassword, targetPassword, readerPassword);

        HttpResult deniedDirectory = request("GET", "/api/admin/users", readerToken, null);
        assertStatus(deniedDirectory, 403);
        assertNoStore(deniedDirectory, "denied non-ADMIN account directory");
        HttpResult deniedChange = update(readerToken, targetId, "SECURITY_ANALYST", true);
        assertStatus(deniedChange, 403);
        assertNoStore(deniedChange, "denied non-ADMIN account update");

        HttpResult promoted = update(admin.accessToken(), targetId, "ADMIN", true);
        assertStatus(promoted, 200);
        assertNoStore(promoted, "role update");
        assertThat(promoted.body().path("role").asText()).isEqualTo("ADMIN");
        assertThat(promoted.body().path("enabled").asBoolean()).isTrue();
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + targetId)).isEqualTo("2");
        assertStatus(request("GET", "/api/auth/me", targetToken, null), 401);
        assertStatus(request("GET", "/api/auth/me", admin.accessToken(), null), 200);

        HttpResult promotedLogin = login(targetUsername, targetPassword);
        assertStatus(promotedLogin, 200);
        assertNoStore(promotedLogin, "updated administrator MFA challenge");
        assertThat(promotedLogin.body().path("mfaRequired").asBoolean()).isTrue();
        assertThat(promotedLogin.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(promotedLogin.body().path("accessToken").isNull()).isTrue();
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + targetId
        )).isEqualTo("1");

        HttpResult noOp = update(admin.accessToken(), targetId, "ADMIN", true);
        assertStatus(noOp, 200);
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + targetId)).isEqualTo("2");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.user_account_audits WHERE target_user_id = " + targetId))
                .isEqualTo("1");

        HttpResult disabled = update(admin.accessToken(), targetId, "ADMIN", false);
        assertStatus(disabled, 200);
        assertNoStore(disabled, "account disable");
        assertThat(disabled.body().path("enabled").asBoolean()).isFalse();
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + targetId)).isEqualTo("3");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + targetId
        )).isEqualTo("0");
        assertStatus(request("GET", "/api/auth/me", targetToken, null), 401);
        assertStatus(login(targetUsername, targetPassword), 401);

        HttpResult enabled = update(admin.accessToken(), targetId, "ADMIN", true);
        assertStatus(enabled, 200);
        assertNoStore(enabled, "account re-enable");
        assertThat(enabled.body().path("enabled").asBoolean()).isTrue();
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + targetId)).isEqualTo("4");
        HttpResult reenabledLogin = login(targetUsername, targetPassword);
        assertStatus(reenabledLogin, 200);
        assertNoStore(reenabledLogin, "re-enabled MFA challenge");
        assertThat(reenabledLogin.body().path("mfaRequired").asBoolean()).isTrue();
        assertThat(reenabledLogin.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(reenabledLogin.body().path("accessToken").isNull()).isTrue();
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + targetId
        )).isEqualTo("1");

        HttpResult selfUpdate = update(admin.accessToken(), adminId, "USER", true);
        assertStatus(selfUpdate, 400);
        assertNoStore(selfUpdate, "rejected self-demotion");
        HttpResult deviceRole = update(admin.accessToken(), targetId, "DEVICE", true);
        assertStatus(deviceRole, 400);
        assertNoStore(deviceRole, "rejected internal DEVICE role assignment");

        TemporaryTrigger rejectAudit = installRejectingAccountAuditTrigger(targetId);
        try {
            HttpResult failedAudit = update(admin.accessToken(), targetId, "USER", true);
            assertThat(failedAudit.status())
                    .as("account update must roll back if the audit insert fails; body: %s", failedAudit.body())
                    .isIn(409, 500);
            assertNoStore(failedAudit, "account update rolled back after audit failure");
        } finally {
            dropTemporaryTrigger(rejectAudit);
        }

        assertThat(queryDatabase("SELECT role FROM public.users WHERE id = " + targetId)).isEqualTo("ADMIN");
        assertThat(queryDatabase("SELECT enabled FROM public.users WHERE id = " + targetId)).isEqualTo("t");
        assertThat(queryDatabase("SELECT mfa_auth_version FROM public.users WHERE id = " + targetId)).isEqualTo("4");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_login_challenges WHERE user_id = " + targetId
        )).isEqualTo("1");
        assertThat(queryDatabase("SELECT COUNT(*) FROM public.user_account_audits WHERE target_user_id = " + targetId))
                .isEqualTo("3");

        HttpResult auditHistory = request(
                "GET",
                "/api/admin/users/audits?targetUsername=" + targetUsername + "&size=20",
                admin.accessToken(),
                null
        );
        assertStatus(auditHistory, 200);
        assertNoStore(auditHistory, "account lifecycle audit history");
        assertThat(auditHistory.body().path("totalElements").asLong()).isEqualTo(3);
        JsonNode firstChange = auditHistory.body().path("content").get(2);
        assertThat(firstChange.path("operation").asText()).isEqualTo("ACCOUNT_UPDATED");
        assertThat(firstChange.path("targetUserId").asLong()).isEqualTo(Long.parseLong(targetId));
        assertThat(firstChange.path("targetUsername").asText()).isEqualTo(targetUsername);
        assertThat(firstChange.path("previousRole").asText()).isEqualTo("SECURITY_ANALYST");
        assertThat(firstChange.path("newRole").asText()).isEqualTo("ADMIN");
        assertThat(firstChange.path("previousEnabled").asBoolean()).isTrue();
        assertThat(firstChange.path("newEnabled").asBoolean()).isTrue();
        assertThat(firstChange.path("actorUserId").asLong()).isEqualTo(Long.parseLong(adminId));
        assertThat(firstChange.path("actorUsername").asText()).isEqualTo(adminUsername);
        assertThat(auditHistory.body().toString()).doesNotContain(adminPassword, targetPassword, readerPassword);

        CommandResult appendOnly = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.user_account_audits SET actor_username = 'tampered' WHERE target_user_id = " + targetId
        );
        assertThat(appendOnly.exitCode()).isNotZero();
        assertThat(appendOnly.output()).contains("Audit history is append-only");
        assertStatus(request("GET", "/api/auth/me", admin.accessToken(), null), 200);
    }

    private JsonNode findByUsername(JsonNode accounts, String username) {
        for (JsonNode account : accounts) {
            if (username.equals(account.path("username").asText())) return account;
        }
        throw new AssertionError("Account directory did not include " + username);
    }

    private EnrolledAccount enrollPrivilegedAccount(String username, String password) throws Exception {
        HttpResult login = login(username, password);
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
        return new EnrolledAccount(accessToken);
    }

    private HttpResult login(String username, String password) throws Exception {
        return request("POST", "/api/auth/login", null, credentials(username, password));
    }

    private HttpResult update(String token, String id, String role, boolean enabled) throws Exception {
        return request(
                "PUT",
                "/api/admin/users/" + id,
                token,
                JSON.createObjectNode().put("role", role).put("enabled", enabled)
        );
    }

    private TemporaryTrigger installRejectingAccountAuditTrigger(String targetId) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase27_reject_" + suffix;
        String triggerName = "phase27_reject_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.target_user_id = " + targetId + " THEN "
                + "RAISE EXCEPTION 'Phase 27 injected account audit failure' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public.user_account_audits "
                + "FOR EACH ROW EXECUTE FUNCTION public." + functionName + "();";
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_COMMAND, sql);
        assertThat(result.exitCode())
                .as("temporary account-audit failure trigger should install; output: %s", result.output())
                .isZero();
        return new TemporaryTrigger(triggerName, functionName);
    }

    private void dropTemporaryTrigger(TemporaryTrigger trigger) throws Exception {
        CommandResult result = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DROP TRIGGER IF EXISTS " + trigger.triggerName() + " ON public.user_account_audits; "
                        + "DROP FUNCTION IF EXISTS public." + trigger.functionName() + "();"
        );
        assertThat(result.exitCode())
                .as("temporary account-audit failure trigger should be removed; output: %s", result.output())
                .isZero();
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase27-account-test", sql);
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

    private record EnrolledAccount(String accessToken) {
    }

    private record TemporaryTrigger(String triggerName, String functionName) {
    }

    private record HttpResult(int status, JsonNode body, String cacheControl) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
