package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit atomicity, append-only database safeguards, and stable paging checks for the Compose stack. */
@EnabledIfEnvironmentVariable(named = "PHASE13_INTEGRATION", matches = "true")
class Phase13AuditIntegrityComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String PSQL_COMMAND =
            "psql -v ON_ERROR_STOP=1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";
    private static final String PSQL_QUERY_COMMAND =
            "psql -v ON_ERROR_STOP=1 -t -A -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl = environment("PHASE13_BASE_URL", "http://127.0.0.1:8080");
    private final List<TemporaryTrigger> temporaryTriggers = new ArrayList<>();

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void auditWritesAreAtomicHistoryIsAppendOnlyAndPageTiesUseDescendingIds() throws Exception {
        String adminUsername = environment("PHASE13_ADMIN_USERNAME", "admin")
                .trim()
                .toLowerCase(Locale.ROOT);
        String adminToken = login(adminUsername, requiredEnvironment("PHASE13_ADMIN_PASSWORD", "ADMIN_PASSWORD"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
        String ownerUsername = "phase13-owner-" + suffix;
        String ownerPassword = "Phase13!" + suffix + "Aa";
        registerUser(ownerUsername, ownerPassword);
        String ownerToken = login(ownerUsername, ownerPassword);

        JsonNode provisioned = createDevice(adminToken, suffix);
        JsonNode device = provisioned.path("device");
        long deviceId = device.path("id").asLong();
        long adminId = device.path("ownerId").asLong();
        String deviceCode = device.path("deviceCode").asText();
        assertThat(device.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(adminId).isPositive();

        try {
            TemporaryTrigger rejectStatusAudit = installRejectingTrigger(
                    "device_status_audits", "INSERT", "NEW.device_id = " + deviceId
            );
            try {
                assertDatabaseFailure(patchStatus(adminToken, deviceId, "BLOCKED"));
            } finally {
                dropTemporaryTrigger(rejectStatusAudit);
            }
            assertThat(getDevice(adminToken, deviceId).path("status").asText())
                    .as("status mutation must roll back when its audit insert fails")
                    .isEqualTo("ACTIVE");
            assertThat(statusAudits(adminToken, deviceId)).isEmpty();

            TemporaryTrigger equalStatusTimes = installEqualTimestampTrigger("device_status_audits", deviceId);
            try {
                assertStatus(patchStatus(adminToken, deviceId, "BLOCKED"), 200);
                assertStatus(patchStatus(adminToken, deviceId, "ACTIVE"), 200);
            } finally {
                dropTemporaryTrigger(equalStatusTimes);
            }

            List<JsonNode> statusHistory = statusAudits(adminToken, deviceId);
            assertThat(statusHistory).hasSize(2);
            assertThat(statusHistory.get(0).path("changedAt").asText())
                    .isEqualTo(statusHistory.get(1).path("changedAt").asText());
            long newestStatusAuditId = statusHistory.get(0).path("id").asLong();
            long olderStatusAuditId = statusHistory.get(1).path("id").asLong();
            assertThat(newestStatusAuditId).isGreaterThan(olderStatusAuditId);

            HttpResult firstStatusPage = request(
                    "GET", "/api/devices/" + deviceId + "/status-audits?page=0&size=1", adminToken, null
            );
            HttpResult secondStatusPage = request(
                    "GET", "/api/devices/" + deviceId + "/status-audits?page=1&size=1", adminToken, null
            );
            assertStatus(firstStatusPage, 200);
            assertStatus(secondStatusPage, 200);
            assertThat(firstStatusPage.body().path("content").get(0).path("id").asLong())
                    .isEqualTo(newestStatusAuditId);
            assertThat(firstStatusPage.body().path("hasNext").asBoolean()).isTrue();
            assertThat(secondStatusPage.body().path("content").get(0).path("id").asLong())
                    .isEqualTo(olderStatusAuditId);
            assertThat(secondStatusPage.body().path("hasPrevious").asBoolean()).isTrue();
            assertThat(secondStatusPage.body().path("hasNext").asBoolean()).isFalse();

            TemporaryTrigger rejectOwnershipAudit = installRejectingTrigger(
                    "device_ownership_audits", "INSERT", "NEW.device_id = " + deviceId
            );
            try {
                assertDatabaseFailure(transferOwnership(adminToken, deviceId, ownerUsername));
            } finally {
                dropTemporaryTrigger(rejectOwnershipAudit);
            }
            assertThat(getDevice(adminToken, deviceId).path("ownerUsername").asText())
                    .as("owner mutation must roll back when its audit insert fails")
                    .isEqualTo(adminUsername);
            assertThat(ownershipAudits(adminToken, deviceId)).isEmpty();

            assertStatus(transferOwnership(adminToken, deviceId, ownerUsername), 200);
            List<JsonNode> ownershipHistory = ownershipAudits(adminToken, deviceId);
            assertThat(ownershipHistory).hasSize(1);
            long ownershipAuditId = ownershipHistory.getFirst().path("id").asLong();
            assertThat(getDevice(adminToken, deviceId).path("ownerUsername").asText()).isEqualTo(ownerUsername);

            HttpResult accessCheck = request(
                    "POST",
                    "/api/access/check",
                    ownerToken,
                    JSON.createObjectNode()
                            .put("deviceCode", deviceCode)
                            .put("resource", "phase13-no-match-" + suffix)
                            .put("action", "READ")
            );
            assertStatus(accessCheck, 200);
            assertThat(accessCheck.body().path("decision").asText()).isEqualTo("DENY");
            assertThat(accessCheck.body().path("reason").asText()).isEqualTo("NO_MATCHING_POLICY");
            long accessAuditId = accessCheck.body().path("auditId").asLong();
            assertThat(accessAuditId).isPositive();

            String policyName = "Phase 13 atomic rule " + suffix;
            ObjectNode baselinePolicy = policyBody(policyName, "phase13-" + suffix, "ALLOW", true);
            HttpResult policyCreated = request("POST", "/api/policies", adminToken, baselinePolicy);
            assertStatus(policyCreated, 201);
            long policyId = policyCreated.body().path("id").asLong();
            List<JsonNode> createHistory = policyAudits(adminToken, policyId);
            assertThat(createHistory).hasSize(1);
            long policyAuditId = createHistory.getFirst().path("id").asLong();

            String failedCreateName = "Phase 13 failed create " + suffix;
            TemporaryTrigger rejectPolicyAudit = installRejectingTrigger(
                    "policy_change_audits",
                    "INSERT",
                    "NEW.policy_id = " + policyId + " OR NEW.after_name = '" + failedCreateName + "'"
            );
            try {
                ObjectNode failedCreate = policyBody(failedCreateName, "failed-" + suffix, "ALLOW", true);
                assertDatabaseFailure(request("POST", "/api/policies", adminToken, failedCreate));
                assertThat(policies(adminToken))
                        .noneMatch(policy -> policy.path("name").asText().equals(failedCreateName));
                CommandResult failedCreateAuditCount = executePsqlQuery(
                        "SELECT COUNT(*) FROM public.policy_change_audits WHERE after_name = '" + failedCreateName + "'"
                );
                assertThat(failedCreateAuditCount.exitCode()).isZero();
                assertThat(failedCreateAuditCount.output().trim()).isEqualTo("0");

                ObjectNode failedUpdate = policyBody(policyName, "changed-" + suffix, "DENY", false);
                assertDatabaseFailure(request("PUT", "/api/policies/" + policyId, adminToken, failedUpdate));
                JsonNode afterFailedUpdate = getPolicy(adminToken, policyId);
                assertThat(afterFailedUpdate.path("effect").asText()).isEqualTo("ALLOW");
                assertThat(afterFailedUpdate.path("enabled").asBoolean()).isTrue();
                assertThat(policyAudits(adminToken, policyId)).hasSize(1);
            } finally {
                dropTemporaryTrigger(rejectPolicyAudit);
            }

            TemporaryTrigger rejectPolicyDelete = installRejectingTrigger(
                    "policies", "DELETE", "OLD.id = " + policyId
            );
            try {
                assertDatabaseFailure(request("DELETE", "/api/policies/" + policyId, adminToken, null));
            } finally {
                dropTemporaryTrigger(rejectPolicyDelete);
            }
            assertThat(getPolicy(adminToken, policyId).path("effect").asText()).isEqualTo("ALLOW");
            assertThat(policyAudits(adminToken, policyId))
                    .as("failed policy deletion must roll back the preceding DELETE audit insert")
                    .hasSize(1);

            assertAuditRowsRejectUpdatesAndDeletes(List.of(
                    new AuditRow("access_audits", accessAuditId, "requester_username"),
                    new AuditRow("device_ownership_audits", ownershipAuditId, "changed_by_username"),
                    new AuditRow("device_status_audits", newestStatusAuditId, "changed_by_username"),
                    new AuditRow("policy_change_audits", policyAuditId, "changed_by_username")
            ));

            assertThat(audits(adminToken, deviceCode)).hasSize(1);
            assertThat(ownershipAudits(adminToken, deviceId)).hasSize(1);
            assertThat(statusAudits(adminToken, deviceId)).hasSize(2);
            assertThat(policyAudits(adminToken, policyId)).hasSize(1);
        } finally {
            while (!temporaryTriggers.isEmpty()) {
                dropTemporaryTrigger(temporaryTriggers.getLast());
            }
        }
    }

    private void assertAuditRowsRejectUpdatesAndDeletes(List<AuditRow> rows) throws Exception {
        for (AuditRow row : rows) {
            String table = "public." + row.table();
            String update = "UPDATE " + table + " SET " + row.updateColumn()
                    + " = " + row.updateColumn() + " WHERE id = " + row.id();
            CommandResult updateResult = executePsql(update);
            assertSqlRejected(updateResult, "updating " + row.table());

            CommandResult deleteResult = executePsql(
                    "DELETE FROM " + table + " WHERE id = " + row.id()
            );
            assertSqlRejected(deleteResult, "deleting from " + row.table());
        }
    }

    private void assertSqlRejected(CommandResult result, String operation) {
        assertThat(result.exitCode())
                .as("PostgreSQL must reject %s; output: %s", operation, result.output())
                .isNotZero();
        assertThat(result.output().toLowerCase(Locale.ROOT))
                .as("append-only trigger error for %s", operation)
                .contains("audit history is append-only");
    }

    private TemporaryTrigger installRejectingTrigger(String table, String event, String condition) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase13_reject_" + suffix;
        String triggerName = "phase13_trigger_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'Phase 13 injected audit failure' USING ERRCODE = '23514'; RETURN NULL; "
                + "END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE " + event + " ON public." + table + " "
                + "FOR EACH ROW WHEN (" + condition + ") "
                + "EXECUTE FUNCTION public." + functionName + "();";
        executePsqlSuccessfully(sql);
        TemporaryTrigger trigger = new TemporaryTrigger(table, triggerName, functionName);
        temporaryTriggers.add(trigger);
        return trigger;
    }

    private TemporaryTrigger installEqualTimestampTrigger(String table, long deviceId) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String functionName = "phase13_fixed_time_" + suffix;
        String triggerName = "phase13_fixed_time_" + suffix;
        String sql = "CREATE FUNCTION public." + functionName + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "NEW.changed_at := TIMESTAMPTZ '2000-01-01 00:00:00+00'; RETURN NEW; "
                + "END; $$; "
                + "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON public." + table + " "
                + "FOR EACH ROW WHEN (NEW.device_id = " + deviceId + ") "
                + "EXECUTE FUNCTION public." + functionName + "();";
        executePsqlSuccessfully(sql);
        TemporaryTrigger trigger = new TemporaryTrigger(table, triggerName, functionName);
        temporaryTriggers.add(trigger);
        return trigger;
    }

    private void dropTemporaryTrigger(TemporaryTrigger trigger) throws Exception {
        executePsqlSuccessfully("DROP TRIGGER IF EXISTS " + trigger.triggerName() + " ON public." + trigger.table() + "; "
                + "DROP FUNCTION IF EXISTS public." + trigger.functionName + "();");
        temporaryTriggers.remove(trigger);
    }

    private void executePsqlSuccessfully(String sql) throws Exception {
        CommandResult result = executePsql(sql);
        assertThat(result.exitCode())
                .as("PostgreSQL command should succeed; output: %s", result.output())
                .isZero();
    }

    private CommandResult executePsql(String sql) throws Exception {
        return runPsql(PSQL_COMMAND, sql);
    }

    private CommandResult executePsqlQuery(String sql) throws Exception {
        return runPsql(PSQL_QUERY_COMMAND, sql);
    }

    private CommandResult runPsql(String command, String sql) throws Exception {
        Path root = repositoryRoot();
        Process process = new ProcessBuilder(
                "docker", "compose", "exec", "-T", "postgres",
                "sh", "-c", command
        ).directory(root.toFile()).redirectErrorStream(true).start();

        // Write the SQL script to psql's stdin. Passing a multi-statement script
        // through the command line breaks on Windows because ProcessBuilder and
        // docker.exe disagree on argument escaping, so psql only receives "CREATE".
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(sql.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out waiting for the Compose PostgreSQL command");
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

    private JsonNode createDevice(String adminToken, String suffix) throws Exception {
        String deviceCode = "PHASE13-SENSOR-" + suffix.toUpperCase(Locale.ROOT);
        ObjectNode body = JSON.createObjectNode()
                .put("deviceCode", deviceCode)
                .put("deviceName", "Phase 13 audit integrity device")
                .put("deviceType", "SENSOR")
                .put("ipAddress", "192.168.253.8")
                .put("mqttClientId", deviceCode);
        HttpResult result = request("POST", "/api/devices", adminToken, body);
        assertStatus(result, 201);
        return result.body();
    }

    private void registerUser(String username, String password) throws Exception {
        ObjectNode body = JSON.createObjectNode()
                .put("username", username)
                .put("password", password)
                .put("fullName", "Phase 13 Audit Test Owner");
        assertStatus(request("POST", "/api/auth/register", null, body), 201);
    }

    private String login(String username, String password) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("username", username).put("password", password);
        HttpResult result = request("POST", "/api/auth/login", null, body);
        assertStatus(result, 200);
        String token = result.body().path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResult patchStatus(String adminToken, long deviceId, String status) throws Exception {
        return request(
                "PATCH",
                "/api/devices/" + deviceId + "/status",
                adminToken,
                JSON.createObjectNode().put("status", status)
        );
    }

    private HttpResult transferOwnership(String adminToken, long deviceId, String ownerUsername) throws Exception {
        return request(
                "PATCH",
                "/api/devices/" + deviceId + "/owner",
                adminToken,
                JSON.createObjectNode().put("ownerUsername", ownerUsername)
        );
    }

    private JsonNode getDevice(String adminToken, long deviceId) throws Exception {
        HttpResult result = request("GET", "/api/devices/" + deviceId, adminToken, null);
        assertStatus(result, 200);
        return result.body();
    }

    private JsonNode getPolicy(String adminToken, long policyId) throws Exception {
        HttpResult result = request("GET", "/api/policies/" + policyId, adminToken, null);
        assertStatus(result, 200);
        return result.body();
    }

    private ObjectNode policyBody(String name, String resource, String effect, boolean enabled) {
        return JSON.createObjectNode()
                .put("name", name)
                .put("subject", "SENSOR")
                .put("resource", resource)
                .put("action", "READ")
                .put("effect", effect)
                .put("enabled", enabled)
                .put("description", "Phase 13 audit integrity fixture");
    }

    private List<JsonNode> policies(String adminToken) throws Exception {
        HttpResult result = request("GET", "/api/policies", adminToken, null);
        assertStatus(result, 200);
        List<JsonNode> values = new ArrayList<>();
        result.body().forEach(values::add);
        return values;
    }

    private List<JsonNode> audits(String adminToken, String deviceCode) throws Exception {
        return pageContent(request(
                "GET", "/api/access/audits?deviceCode=" + deviceCode, adminToken, null
        ), 200);
    }

    private List<JsonNode> ownershipAudits(String adminToken, long deviceId) throws Exception {
        return pageContent(request(
                "GET", "/api/devices/" + deviceId + "/ownership-audits", adminToken, null
        ), 200);
    }

    private List<JsonNode> statusAudits(String adminToken, long deviceId) throws Exception {
        return pageContent(request(
                "GET", "/api/devices/" + deviceId + "/status-audits", adminToken, null
        ), 200);
    }

    private List<JsonNode> policyAudits(String adminToken, long policyId) throws Exception {
        return pageContent(request("GET", "/api/policies/" + policyId + "/audits", adminToken, null), 200);
    }

    private List<JsonNode> pageContent(HttpResult result, int expectedStatus) {
        assertStatus(result, expectedStatus);
        JsonNode content = result.body().path("content");
        assertThat(content.isArray()).as("paged history response content").isTrue();
        List<JsonNode> values = new ArrayList<>();
        content.forEach(values::add);
        return values;
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

    private void assertDatabaseFailure(HttpResult result) {
        assertThat(result.status())
                .as("injected audit/database failure should reject the mutation; response: %s", result.body())
                .isIn(409, 500);
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 13 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record TemporaryTrigger(String table, String triggerName, String functionName) {
    }

    private record AuditRow(String table, long id, String updateColumn) {
    }
}