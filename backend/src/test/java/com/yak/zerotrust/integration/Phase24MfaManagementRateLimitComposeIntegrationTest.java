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

/** Verifies that authenticated MFA-management proofs share the bounded per-peer authentication limiter. */
@EnabledIfEnvironmentVariable(named = "PHASE24_INTEGRATION", matches = "true")
class Phase24MfaManagementRateLimitComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE24_BASE_URL", "http://127.0.0.1:8080");
    private final int maxAttempts = Integer.parseInt(environment("PHASE24_MAX_ATTEMPTS", "20"));
    private final int windowSeconds = Integer.parseInt(environment("PHASE24_WINDOW_SECONDS", "60"));

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void rateLimitsAuthenticatedRecoveryCodeRotationProofsBeforeMfaVerification() throws Exception {
        assertThat(maxAttempts).as("the setup flow needs three admitted authentication requests").isGreaterThan(3);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String username = "phase24-analyst-" + suffix;
        String password = "Analyst24!" + suffix + "Aa";
        register(username, password);
        promoteToAnalyst(username);

        HttpResult passwordLogin = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(passwordLogin, 200);
        assertNoStore(passwordLogin, "required enrollment challenge");
        assertThat(passwordLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        String enrollmentToken = passwordLogin.body().path("enrollmentToken").asText();

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
        String accessToken = completed.body().path("session").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        long lastAcceptedCounter = Long.parseLong(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        ));
        ObjectNode invalidRotation = JSON.createObjectNode()
                .put("password", password)
                .put("code", "not-a-code");
        int admittedRotationAttempts = maxAttempts - 3;
        for (int attempt = 0; attempt < admittedRotationAttempts; attempt++) {
            HttpResult rejected = request(
                    "POST",
                    "/api/auth/mfa/recovery-codes/rotate",
                    accessToken,
                    invalidRotation
            );
            assertStatus(rejected, 400);
            assertNoStore(rejected, "rejected recovery-code rotation proof");
        }

        HttpResult limited = request(
                "POST",
                "/api/auth/mfa/recovery-codes/rotate",
                accessToken,
                invalidRotation
        );
        assertStatus(limited, 429);
        assertNoStore(limited, "rate-limited recovery-code rotation");
        assertThat(limited.body().path("error").asText()).isEqualTo("AUTH_RATE_LIMITED");
        assertThat(limited.body().path("message").asText())
                .isEqualTo("Too many authentication attempts.");
        assertThat(limited.retryAfterSeconds()).isNotBlank();
        assertThat(Long.parseLong(limited.retryAfterSeconds())).isBetween(1L, (long) windowSeconds);

        String userIdSql = "(SELECT id FROM public.users WHERE username = '" + username + "')";
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_recovery_codes WHERE user_id = " + userIdSql + " AND used_at IS NULL"
        )).isEqualTo("10");
        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.mfa_security_audits WHERE user_id = " + userIdSql
                        + " AND operation = 'RECOVERY_CODES_ROTATED'"
        )).isEqualTo("0");
        assertThat(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + username + "'"
        )).isEqualTo(Long.toString(lastAcceptedCounter));
    }

    private void register(String username, String password) throws Exception {
        HttpResult result = request(
                "POST",
                "/api/auth/register",
                null,
                JSON.createObjectNode()
                        .put("username", username)
                        .put("password", password)
                        .put("fullName", "Phase 24 MFA Rate Limit Analyst")
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
                response.headers().firstValue("Cache-Control").orElse(""),
                response.headers().firstValue("Retry-After").orElse(null)
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase24-mfa-test", sql);
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

    private record HttpResult(int status, JsonNode body, String cacheControl, String retryAfterSeconds) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
