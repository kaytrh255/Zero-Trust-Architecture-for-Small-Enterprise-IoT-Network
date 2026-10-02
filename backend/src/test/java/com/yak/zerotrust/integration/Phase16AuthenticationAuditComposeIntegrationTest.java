package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies login-attempt auditing, role restrictions, secret minimization, and append-only storage. */
@EnabledIfEnvironmentVariable(named = "PHASE16_INTEGRATION", matches = "true")
class Phase16AuthenticationAuditComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE16_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void recordsSuccessfulAndRejectedLoginsWithoutExposingCredentials() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String username = "phase16-" + suffix;
        String password = "Phase16!" + suffix + "Aa";
        String wrongPassword = "Wrong16!" + suffix + "Aa";

        ObjectNode registration = JSON.createObjectNode()
                .put("username", username)
                .put("password", password)
                .put("fullName", "Phase 16 Audit Test User");
        HttpResult registered = request("POST", "/api/auth/register", null, registration);
        assertStatus(registered, 201);
        long registeredUserId = registered.body().path("id").asLong();
        assertThat(registeredUserId).isPositive();

        ObjectNode successfulLoginBody = credentials(username, password);
        HttpResult successfulLogin = request("POST", "/api/auth/login", null, successfulLoginBody);
        assertStatus(successfulLogin, 200);
        String userToken = successfulLogin.body().path("accessToken").asText();
        assertThat(userToken).isNotBlank();

        HttpResult rejectedLogin = request(
                "POST",
                "/api/auth/login",
                null,
                credentials(username, wrongPassword)
        );
        assertStatus(rejectedLogin, 401);
        assertThat(rejectedLogin.body().path("message").asText()).isEqualTo("Invalid username or password");

        String unknownUsername = "missing-" + suffix;
        HttpResult unknownLogin = request(
                "POST",
                "/api/auth/login",
                null,
                credentials(unknownUsername, wrongPassword)
        );
        assertStatus(unknownLogin, 401);
        assertThat(unknownLogin.body().path("message").asText())
                .isEqualTo(rejectedLogin.body().path("message").asText());

        String adminToken = loginAsAdmin();
        HttpResult history = request(
                "GET",
                "/api/auth/audits?username=" + username + "&size=10",
                adminToken,
                null
        );
        assertStatus(history, 200);
        JsonNode content = history.body().path("content");
        assertThat(content.isArray()).as("authentication audit page content").isTrue();
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).path("attemptedUsername").asText()).isEqualTo(username);
        assertThat(content.get(0).path("outcome").asText()).isEqualTo("FAILURE");
        assertThat(content.get(1).path("attemptedUsername").asText()).isEqualTo(username);
        assertThat(content.get(1).path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(content.get(1).path("authenticatedUserId").asLong()).isEqualTo(registeredUserId);
        assertThat(content.get(0).has("password")).isFalse();
        assertThat(content.get(1).has("password")).isFalse();
        assertThat(history.body().toString())
                .doesNotContain(password)
                .doesNotContain(wrongPassword)
                .doesNotContain(userToken);

        HttpResult unknownHistory = request(
                "GET",
                "/api/auth/audits?username=" + unknownUsername,
                adminToken,
                null
        );
        assertStatus(unknownHistory, 200);
        assertThat(unknownHistory.body().path("content").size()).isEqualTo(1);
        assertThat(unknownHistory.body().path("content").get(0).path("outcome").asText()).isEqualTo("FAILURE");
        assertThat(queryDatabase(
                "SELECT authenticated_user_id IS NULL FROM public.authentication_attempt_audits "
                        + "WHERE attempted_username = '" + unknownUsername + "'"
        )).isEqualTo("t");

        assertStatus(request("GET", "/api/auth/audits", userToken, null), 403);
        assertAuthenticationHistoryIsAppendOnly(content.get(0).path("id").asLong(), username);
    }

    private void assertAuthenticationHistoryIsAppendOnly(long auditId, String username) throws Exception {
        CommandResult update = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.authentication_attempt_audits SET outcome = 'SUCCESS' WHERE id = " + auditId
        );
        assertSqlRejected(update, "updating authentication-attempt history");

        CommandResult delete = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DELETE FROM public.authentication_attempt_audits WHERE id = " + auditId
        );
        assertSqlRejected(delete, "deleting authentication-attempt history");

        assertThat(queryDatabase(
                "SELECT count(*) FROM public.authentication_attempt_audits WHERE attempted_username = '"
                        + username + "'"
        )).isEqualTo("2");
    }

    private void assertSqlRejected(CommandResult result, String operation) {
        assertThat(result.exitCode())
                .as("database should reject %s; output: %s", operation, result.output())
                .isNotZero();
        assertThat(result.output().toLowerCase(Locale.ROOT))
                .as("database should reject %s with the append-only trigger", operation)
                .contains("audit history is append-only");
    }

    private String loginAsAdmin() throws Exception {
        String username = environment("PHASE16_ADMIN_USERNAME", "admin").trim().toLowerCase(Locale.ROOT);
        String password = requiredEnvironment("PHASE16_ADMIN_PASSWORD", "ADMIN_PASSWORD");
        HttpResult response = request("POST", "/api/auth/login", null, credentials(username, password));
        assertStatus(response, 200);
        String token = response.body().path("accessToken").asText();
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
        return new HttpResult(response.statusCode(), responseBody);
    }

    private String queryDatabase(String sql) throws Exception {
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_QUERY_COMMAND, sql);
        assertThat(result.exitCode())
                .as("database query should succeed; output: %s", result.output())
                .isZero();
        return result.output().trim();
    }

    private CommandResult runDatabaseCommand(String command, String sql) throws Exception {
        return runCompose(
                "exec", "-T", "postgres", "sh", "-c", command, "phase16-auth-audit-test", sql
        );
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
        assertThat(result.status())
                .as("HTTP status; body: %s", result.body())
                .isEqualTo(expectedStatus);
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 16 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
