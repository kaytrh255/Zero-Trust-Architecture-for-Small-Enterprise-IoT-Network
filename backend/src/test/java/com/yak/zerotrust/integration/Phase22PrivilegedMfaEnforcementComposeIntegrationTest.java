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

/** Verifies enforced privileged MFA and the purpose-restricted enrollment-only login flow. */
@EnabledIfEnvironmentVariable(named = "PHASE22_INTEGRATION", matches = "true")
class Phase22PrivilegedMfaEnforcementComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE22_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void enforcesEnrollmentForPrivilegedRolesWithoutIssuingAccessBeforeTotp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String adminUsername = environment("PHASE22_ADMIN_USERNAME", "admin").trim().toLowerCase(Locale.ROOT);
        String adminPassword = requiredEnvironment("PHASE22_ADMIN_PASSWORD", "ADMIN_PASSWORD");

        HttpResult requiredLogin = request("POST", "/api/auth/login", null, credentials(adminUsername, adminPassword));
        assertStatus(requiredLogin, 200);
        assertNoStore(requiredLogin, "required MFA enrollment challenge");
        assertThat(requiredLogin.body().path("mfaRequired").asBoolean()).isFalse();
        assertThat(requiredLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(requiredLogin.body().path("accessToken").isNull()).isTrue();
        assertThat(requiredLogin.body().path("enrollmentToken").asText()).isNotBlank();
        assertThat(requiredLogin.body().path("challengeExpiresInSeconds").asLong()).isEqualTo(600);
        String enrollmentToken = requiredLogin.body().path("enrollmentToken").asText();

        assertThat(queryDatabase(
                "SELECT purpose FROM public.mfa_login_challenges WHERE user_id = "
                        + "(SELECT id FROM public.users WHERE username = '" + adminUsername + "') "
                        + "ORDER BY expires_at DESC LIMIT 1"
        )).isEqualTo("ENROLLMENT");
        assertStatus(request("GET", "/api/auth/me", enrollmentToken, null), 401);
        assertStatus(request("GET", "/api/devices", enrollmentToken, null), 401);
        assertStatus(request("GET", "/api/auth/mfa/status", enrollmentToken, null), 401);

        HttpResult setupStarted = request(
                "POST",
                "/api/auth/mfa/required-enrollment",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken)
        );
        assertStatus(setupStarted, 200);
        assertNoStore(setupStarted, "required MFA setup");
        String secret = setupStarted.body().path("secret").asText();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(setupStarted.body().path("otpauthUri").asText()).contains("otpauth://totp/").contains(secret);
        assertThat(setupStarted.body().path("expiresAt").asText()).isNotBlank();
        String pendingCiphertext = queryDatabase(
                "SELECT mfa_pending_secret_ciphertext FROM public.users WHERE username = '" + adminUsername + "'"
        );
        assertThat(pendingCiphertext).isNotBlank().doesNotContain(secret);

        HttpResult wrongPurpose = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", enrollmentToken).put("code", "000000")
        );
        assertStatus(wrongPurpose, 401);

        long failuresBeforeInvalidCode = Long.parseLong(queryDatabase(
                "SELECT COUNT(*) FROM public.authentication_attempt_audits "
                        + "WHERE attempted_username = '" + adminUsername + "' AND outcome = 'FAILURE'"
        ));
        HttpResult invalidSetupCode = request(
                "POST",
                "/api/auth/mfa/required-enrollment/confirm",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken).put("code", "not-a-code")
        );
        assertStatus(invalidSetupCode, 401);
        assertThat(Long.parseLong(queryDatabase(
                "SELECT COUNT(*) FROM public.authentication_attempt_audits "
                        + "WHERE attempted_username = '" + adminUsername + "' AND outcome = 'FAILURE'"
        ))).isEqualTo(failuresBeforeInvalidCode + 1);

        Instant enrollmentTime = Instant.now();
        String enrollmentCode = totp.generateCode(secret, enrollmentTime);
        HttpResult completion = request(
                "POST",
                "/api/auth/mfa/required-enrollment/confirm",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken).put("code", enrollmentCode)
        );
        assertStatus(completion, 200);
        assertNoStore(completion, "required MFA enrollment completion");
        assertThat(completion.body().path("session").path("mfaRequired").asBoolean()).isFalse();
        assertThat(completion.body().path("session").path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(completion.body().path("session").path("user").path("role").asText()).isEqualTo("ADMIN");
        assertThat(completion.body().path("session").path("accessToken").asText()).isNotBlank();
        assertThat(completion.body().path("recoveryCodes").size()).isEqualTo(10);
        String accessToken = completion.body().path("session").path("accessToken").asText();
        assertStatus(request("GET", "/api/auth/me", accessToken, null), 200);
        assertThat(queryDatabase(
                "SELECT mfa_enabled FROM public.users WHERE username = '" + adminUsername + "'"
        )).isEqualTo("t");

        HttpResult reusedEnrollmentChallenge = request(
                "POST",
                "/api/auth/mfa/required-enrollment/confirm",
                null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken).put("code", "not-a-code")
        );
        assertStatus(reusedEnrollmentChallenge, 401);
        assertStatus(request("POST", "/api/auth/mfa/required-enrollment", null,
                JSON.createObjectNode().put("enrollmentToken", enrollmentToken)), 401);

        long lastAcceptedCounter = Long.parseLong(queryDatabase(
                "SELECT mfa_last_totp_counter FROM public.users WHERE username = '" + adminUsername + "'"
        ));
        waitForCounterAfter(lastAcceptedCounter);
        HttpResult mfaLogin = request("POST", "/api/auth/login", null, credentials(adminUsername, adminPassword));
        assertStatus(mfaLogin, 200);
        assertThat(mfaLogin.body().path("mfaRequired").asBoolean()).isTrue();
        assertThat(mfaLogin.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(mfaLogin.body().path("accessToken").isNull()).isTrue();
        String loginChallenge = mfaLogin.body().path("mfaToken").asText();
        assertThat(loginChallenge).isNotBlank();
        String loginCode = totp.generateCode(secret, Instant.now());
        HttpResult verifiedLogin = request(
                "POST",
                "/api/auth/mfa/verify",
                null,
                JSON.createObjectNode().put("mfaToken", loginChallenge).put("code", loginCode)
        );
        assertStatus(verifiedLogin, 200);
        assertThat(verifiedLogin.body().path("accessToken").asText()).isNotBlank();
        assertStatus(request("GET", "/api/auth/me", verifiedLogin.body().path("accessToken").asText(), null), 200);

        String userUsername = "phase22-user-" + suffix;
        String userPassword = "User22!" + suffix + "Aa";
        register(userUsername, userPassword, "Phase 22 Standard User");
        HttpResult userLogin = request("POST", "/api/auth/login", null, credentials(userUsername, userPassword));
        assertStatus(userLogin, 200);
        assertThat(userLogin.body().path("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(userLogin.body().path("accessToken").asText()).isNotBlank();

        String analystUsername = "phase22-analyst-" + suffix;
        String analystPassword = "Analyst22!" + suffix + "Aa";
        register(analystUsername, analystPassword, "Phase 22 Security Analyst");
        promoteToAnalyst(analystUsername);
        HttpResult analystLogin = request("POST", "/api/auth/login", null, credentials(analystUsername, analystPassword));
        assertStatus(analystLogin, 200);
        assertThat(analystLogin.body().path("mfaRequired").asBoolean()).isFalse();
        assertThat(analystLogin.body().path("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(analystLogin.body().path("accessToken").isNull()).isTrue();
        assertThat(analystLogin.body().path("challengeExpiresInSeconds").asLong()).isEqualTo(600);
        assertThat(analystLogin.body().path("enrollmentToken").asText()).isNotBlank();
        assertStatus(request("GET", "/api/auth/me", analystLogin.body().path("enrollmentToken").asText(), null), 401);
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase22-mfa-test", sql);
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 22 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body, String cacheControl) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
