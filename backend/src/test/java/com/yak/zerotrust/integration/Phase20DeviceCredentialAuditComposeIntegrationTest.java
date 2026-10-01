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

/** Verifies credential lifecycle history, role protections, secret minimization, and append-only storage. */
@EnabledIfEnvironmentVariable(named = "PHASE20_INTEGRATION", matches = "true")
class Phase20DeviceCredentialAuditComposeIntegrationTest {

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
    private final String baseUrl = environment("PHASE20_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void recordsProvisioningAndRotationWithoutPersistingOneTimeSecrets() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String adminUsername = environment("PHASE20_ADMIN_USERNAME", "admin").trim().toLowerCase(Locale.ROOT);
        String adminToken = login(adminUsername, requiredEnvironment("PHASE20_ADMIN_PASSWORD", "ADMIN_PASSWORD"));
        String deviceCode = "PHASE20-" + suffix.toUpperCase(Locale.ROOT);

        ObjectNode deviceRequest = JSON.createObjectNode()
                .put("deviceCode", deviceCode)
                .put("deviceName", "Credential Audit Test Sensor")
                .put("deviceType", "SENSOR")
                .put("ipAddress", "10.20.30.40")
                .put("mqttClientId", deviceCode);
        HttpResult provisioned = request("POST", "/api/devices", adminToken, deviceRequest);
        assertStatus(provisioned, 201);
        assertNoStore(provisioned, "device provisioning");

        long deviceId = provisioned.body().path("device").path("id").asLong();
        assertThat(deviceId).isPositive();
        String firstPassword = provisioned.body().path("mqttPassword").asText();
        String firstPrivateKey = provisioned.body().path("mqttSigningPrivateKey").asText();
        assertThat(firstPassword).isNotBlank();
        assertThat(firstPrivateKey).isNotBlank();

        String auditPath = "/api/devices/" + deviceId + "/credential-audits";
        HttpResult provisionHistory = request("GET", auditPath + "?size=10", adminToken, null);
        assertStatus(provisionHistory, 200);
        assertThat(provisionHistory.body().path("totalElements").asLong()).isEqualTo(1);
        JsonNode provisionEvent = provisionHistory.body().path("content").get(0);
        assertThat(provisionEvent.path("operation").asText()).isEqualTo("PROVISION");
        assertThat(provisionEvent.path("deviceCode").asText()).isEqualTo(deviceCode);
        assertThat(provisionEvent.path("changedByUsername").asText()).isEqualTo(adminUsername);
        assertThat(provisionEvent.path("previousSigningKeyFingerprint").isNull()).isTrue();
        String firstFingerprint = provisionEvent.path("newSigningKeyFingerprint").asText();
        assertThat(firstFingerprint).matches("[0-9a-f]{64}");
        assertThat(provisionHistory.body().toString())
                .doesNotContain(firstPassword)
                .doesNotContain(firstPrivateKey)
                .doesNotContain("mqttPassword")
                .doesNotContain("mqttSigningPrivateKey");

        String originalPublicKey = queryDatabase(
                "SELECT mqtt_signing_public_key FROM public.devices WHERE id = " + deviceId
        );
        TemporaryTrigger rejectAuditInsert = installRejectingAuditInsertTrigger(deviceId);
        try {
            HttpResult failedRotation = request(
                    "POST", "/api/devices/" + deviceId + "/credentials/rotate", adminToken, null
            );
            assertThat(failedRotation.status())
                    .as("audit insert failure must reject credential rotation; response: %s", failedRotation.body())
                    .isIn(409, 500);
        } finally {
            dropTemporaryTrigger(rejectAuditInsert);
        }
        assertThat(queryDatabase(
                "SELECT mqtt_signing_public_key FROM public.devices WHERE id = " + deviceId
        )).as("failed credential auditing must roll back the device public-key update")
                .isEqualTo(originalPublicKey);
        assertThat(request("GET", auditPath, adminToken, null).body().path("totalElements").asLong())
                .as("a failed rotation must not leave a credential lifecycle event")
                .isEqualTo(1);

        HttpResult rotated = request("POST", "/api/devices/" + deviceId + "/credentials/rotate", adminToken, null);
        assertStatus(rotated, 200);
        assertNoStore(rotated, "credential rotation");
        String secondPassword = rotated.body().path("mqttPassword").asText();
        String secondPrivateKey = rotated.body().path("mqttSigningPrivateKey").asText();
        assertThat(secondPassword).isNotBlank().isNotEqualTo(firstPassword);
        assertThat(secondPrivateKey).isNotBlank().isNotEqualTo(firstPrivateKey);

        HttpResult latestPage = request("GET", auditPath + "?page=0&size=1", adminToken, null);
        HttpResult olderPage = request("GET", auditPath + "?page=1&size=1", adminToken, null);
        assertStatus(latestPage, 200);
        assertStatus(olderPage, 200);
        assertThat(latestPage.body().path("hasNext").asBoolean()).isTrue();
        assertThat(olderPage.body().path("hasPrevious").asBoolean()).isTrue();
        JsonNode rotationEvent = latestPage.body().path("content").get(0);
        assertThat(rotationEvent.path("operation").asText()).isEqualTo("ROTATE");
        assertThat(rotationEvent.path("previousSigningKeyFingerprint").asText()).isEqualTo(firstFingerprint);
        String secondFingerprint = rotationEvent.path("newSigningKeyFingerprint").asText();
        assertThat(secondFingerprint).matches("[0-9a-f]{64}").isNotEqualTo(firstFingerprint);
        assertThat(rotationEvent.path("changedByUsername").asText()).isEqualTo(adminUsername);
        assertThat(olderPage.body().path("content").get(0).path("operation").asText()).isEqualTo("PROVISION");

        HttpResult filtered = request(
                "GET",
                auditPath + "?operation=ROTATE&changedByUsername=" + adminUsername,
                adminToken,
                null
        );
        assertStatus(filtered, 200);
        assertThat(filtered.body().path("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.body().path("content").get(0).path("operation").asText()).isEqualTo("ROTATE");
        assertThat(filtered.body().toString())
                .doesNotContain(firstPassword)
                .doesNotContain(firstPrivateKey)
                .doesNotContain(secondPassword)
                .doesNotContain(secondPrivateKey);

        String analystUsername = "phase20-analyst-" + suffix;
        String analystPassword = "Analyst20!" + suffix + "Aa";
        register(analystUsername, analystPassword, "Phase 20 Security Analyst");
        promoteToAnalyst(analystUsername);
        String analystToken = login(analystUsername, analystPassword);
        assertStatus(request("GET", auditPath, analystToken, null), 200);
        assertStatus(request("POST", "/api/devices/" + deviceId + "/credentials/rotate", analystToken, null), 403);

        String userUsername = "phase20-user-" + suffix;
        String userPassword = "User20!" + suffix + "Aa";
        register(userUsername, userPassword, "Phase 20 Standard User");
        String userToken = login(userUsername, userPassword);
        assertStatus(request("GET", auditPath, userToken, null), 403);

        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'device_credential_audits' "
                        + "AND column_name IN ('mqtt_password', 'mqtt_signing_private_key', 'private_key', 'password')"
        )).isEqualTo("0");
        assertAuditHistoryIsAppendOnly(rotationEvent.path("id").asLong(), deviceId);
    }

    private TemporaryTrigger installRejectingAuditInsertTrigger(long deviceId) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase20_reject_" + suffix;
        String triggerName = "phase20_reject_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'Phase 20 injected credential audit failure' USING ERRCODE = '23514'; RETURN NULL; "
                + "END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public.device_credential_audits "
                + "FOR EACH ROW WHEN (NEW.device_id = " + deviceId + ") "
                + "EXECUTE FUNCTION public." + functionName + "();";
        CommandResult result = runDatabaseCommand(ADMIN_PSQL_COMMAND, sql);
        assertThat(result.exitCode())
                .as("temporary audit-failure trigger should install; output: %s", result.output())
                .isZero();
        return new TemporaryTrigger(triggerName, functionName);
    }

    private void dropTemporaryTrigger(TemporaryTrigger trigger) throws Exception {
        CommandResult result = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DROP TRIGGER IF EXISTS " + trigger.triggerName() + " ON public.device_credential_audits; "
                        + "DROP FUNCTION IF EXISTS public." + trigger.functionName() + "();"
        );
        assertThat(result.exitCode())
                .as("temporary audit-failure trigger should be removed; output: %s", result.output())
                .isZero();
    }

    private void assertAuditHistoryIsAppendOnly(long auditId, long deviceId) throws Exception {
        CommandResult update = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "UPDATE public.device_credential_audits SET changed_by_username = changed_by_username WHERE id = " + auditId
        );
        assertSqlRejected(update, "updating device credential history");

        CommandResult delete = runDatabaseCommand(
                ADMIN_PSQL_COMMAND,
                "DELETE FROM public.device_credential_audits WHERE id = " + auditId
        );
        assertSqlRejected(delete, "deleting device credential history");

        assertThat(queryDatabase(
                "SELECT COUNT(*) FROM public.device_credential_audits WHERE device_id = " + deviceId
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
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, "phase20-credential-audit-test", sql);
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 20 integration checks");
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
