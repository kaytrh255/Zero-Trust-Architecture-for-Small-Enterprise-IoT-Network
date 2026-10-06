package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 11 audit-history and Phase 12 query API checks against the running local Compose stack. */
@EnabledIfEnvironmentVariable(named = "PHASE11_INTEGRATION", matches = "true")
class Phase11ComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl = environment("PHASE11_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void recordsAtomicDeviceStatusAndPolicyHistoryAndRestrictsHistoryReads() throws Exception {
        String adminUsername = environment("PHASE11_ADMIN_USERNAME", "admin")
                .trim()
                .toLowerCase(Locale.ROOT);
        String adminToken = login(adminUsername, requiredEnvironment("PHASE11_ADMIN_PASSWORD", "ADMIN_PASSWORD"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
        String regularUsername = "phase11-user-" + suffix;
        String regularPassword = "Phase11!" + suffix + "Aa";
        registerUser(regularUsername, regularPassword);
        String regularUserToken = login(regularUsername, regularPassword);

        JsonNode provisionedDevice = createDevice(adminToken, suffix);
        JsonNode device = provisionedDevice.path("device");
        long deviceId = device.path("id").asLong();
        long adminId = device.path("ownerId").asLong();
        assertThat(device.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(adminId).isPositive();

        patchStatus(adminToken, deviceId, "BLOCKED", 200);
        assertThat(statusAudits(adminToken, deviceId)).hasSize(1);

        // A valid request that leaves status unchanged, and an invalid request, must not add events.
        patchStatus(adminToken, deviceId, "BLOCKED", 200);
        patchStatus(adminToken, deviceId, "OFFLINE", 400);
        assertThat(statusAudits(adminToken, deviceId)).hasSize(1);

        HttpResult revoke = request("DELETE", "/api/devices/" + deviceId, adminToken, null);
        assertStatus(revoke, 204);
        HttpResult repeatedRevoke = request("DELETE", "/api/devices/" + deviceId, adminToken, null);
        assertStatus(repeatedRevoke, 204);

        var statusHistory = statusAudits(adminToken, deviceId);
        assertThat(statusHistory).hasSize(2);
        JsonNode revoked = statusHistory.get(0);
        assertThat(revoked.path("previousStatus").asText()).isEqualTo("BLOCKED");
        assertThat(revoked.path("newStatus").asText()).isEqualTo("REVOKED");
        assertThat(revoked.path("changedByUserId").asLong()).isEqualTo(adminId);
        assertThat(revoked.path("changedByUsername").asText()).isEqualTo(adminUsername);
        assertThat(revoked.path("changedAt").asText()).isNotBlank();
        JsonNode blocked = statusHistory.get(1);
        assertThat(blocked.path("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(blocked.path("newStatus").asText()).isEqualTo("BLOCKED");

        HttpResult firstStatusPage = request(
                "GET", "/api/devices/" + deviceId + "/status-audits?page=0&size=1", adminToken, null
        );
        assertStatus(firstStatusPage, 200);
        assertThat(firstStatusPage.body().path("content").size()).isEqualTo(1);
        assertThat(firstStatusPage.body().path("page").asInt()).isZero();
        assertThat(firstStatusPage.body().path("size").asInt()).isEqualTo(1);
        assertThat(firstStatusPage.body().path("totalElements").asLong()).isEqualTo(2L);
        assertThat(firstStatusPage.body().path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstStatusPage.body().path("hasNext").asBoolean()).isTrue();

        HttpResult secondStatusPage = request(
                "GET", "/api/devices/" + deviceId + "/status-audits?page=1&size=1", adminToken, null
        );
        assertStatus(secondStatusPage, 200);
        assertThat(secondStatusPage.body().path("content").get(0).path("newStatus").asText())
                .isEqualTo("BLOCKED");
        assertThat(secondStatusPage.body().path("hasPrevious").asBoolean()).isTrue();
        assertThat(secondStatusPage.body().path("hasNext").asBoolean()).isFalse();

        HttpResult filteredStatus = request(
                "GET",
                "/api/devices/" + deviceId
                        + "/status-audits?newStatus=BLOCKED&changedByUsername=" + adminUsername,
                adminToken,
                null
        );
        assertStatus(filteredStatus, 200);
        assertThat(filteredStatus.body().path("totalElements").asLong()).isEqualTo(1L);
        assertThat(filteredStatus.body().path("content").get(0).path("newStatus").asText())
                .isEqualTo("BLOCKED");

        String blockedTimestamp = blocked.path("changedAt").asText();
        String encodedBlockedTimestamp = URLEncoder.encode(blockedTimestamp, StandardCharsets.UTF_8);
        HttpResult filteredByTime = request(
                "GET",
                "/api/devices/" + deviceId + "/status-audits?from=" + encodedBlockedTimestamp
                        + "&to=" + encodedBlockedTimestamp,
                adminToken,
                null
        );
        assertStatus(filteredByTime, 200);
        assertThat(filteredByTime.body().path("totalElements").asLong()).isEqualTo(1L);
        assertThat(filteredByTime.body().path("content").get(0).path("newStatus").asText())
                .isEqualTo("BLOCKED");
        assertStatus(request(
                "GET", "/api/devices/" + deviceId + "/status-audits?size=101", adminToken, null
        ), 400);
        assertStatus(request(
                "GET", "/api/devices/" + deviceId + "/status-audits?newStatus=OFFLINE", adminToken, null
        ), 400);
        assertStatus(request(
                "GET", "/api/devices/" + deviceId + "/status-audits?from="
                        + URLEncoder.encode(revoked.path("changedAt").asText(), StandardCharsets.UTF_8)
                        + "&to=" + encodedBlockedTimestamp,
                adminToken,
                null
        ), 400);
        assertStatus(request(
                "GET", "/api/devices/" + deviceId + "/status-audits", regularUserToken, null
        ), 403);

        ObjectNode originalPolicy = policyBody("Phase 11 audit rule " + suffix, "phase11-" + suffix, "ALLOW", true);
        HttpResult created = request("POST", "/api/policies", adminToken, originalPolicy);
        assertStatus(created, 201);
        long policyId = created.body().path("id").asLong();
        var createHistory = policyAudits(adminToken, policyId);
        assertThat(createHistory).hasSize(1);
        JsonNode createEvent = createHistory.get(0);
        assertThat(createEvent.path("operation").asText()).isEqualTo("CREATE");
        assertThat(createEvent.path("before").isNull() || createEvent.path("before").isMissingNode()).isTrue();
        assertThat(createEvent.path("after").path("effect").asText()).isEqualTo("ALLOW");
        assertThat(createEvent.path("changedByUserId").asLong()).isEqualTo(adminId);
        assertThat(createEvent.path("changedByUsername").asText()).isEqualTo(adminUsername);
        assertThat(createEvent.path("changedAt").asText()).isNotBlank();
        assertStatus(request("GET", "/api/policies/" + policyId + "/audits", regularUserToken, null), 403);

        ObjectNode duplicatePolicy = policyBody("Phase 11 duplicate " + suffix, "other-" + suffix, "ALLOW", true);
        HttpResult duplicateCreated = request("POST", "/api/policies", adminToken, duplicatePolicy);
        assertStatus(duplicateCreated, 201);
        long duplicatePolicyId = duplicateCreated.body().path("id").asLong();
        try {
            HttpResult duplicateCreate = request("POST", "/api/policies", adminToken, originalPolicy);
            assertStatus(duplicateCreate, 409);
            assertThat(policyAudits(adminToken, policyId)).hasSize(1);

            ObjectNode conflictingUpdate = policyBody(
                    duplicatePolicy.path("name").asText(),
                    "changed-" + suffix,
                    "DENY",
                    true
            );
            assertStatus(request("PUT", "/api/policies/" + policyId, adminToken, conflictingUpdate), 409);
            assertThat(policyAudits(adminToken, policyId)).hasSize(1);

            // A semantically identical update is a successful no-op and does not create a second event.
            assertStatus(request("PUT", "/api/policies/" + policyId, adminToken, originalPolicy), 200);
            assertThat(policyAudits(adminToken, policyId)).hasSize(1);

            ObjectNode changedPolicy = policyBody("Phase 11 audit rule " + suffix, "phase11-" + suffix, "DENY", false);
            changedPolicy.put("description", "updated by Phase 11 integration check");
            assertStatus(request("PUT", "/api/policies/" + policyId, adminToken, changedPolicy), 200);
            var updateHistory = policyAudits(adminToken, policyId);
            assertThat(updateHistory).hasSize(2);
            JsonNode updateEvent = updateHistory.get(0);
            assertThat(updateEvent.path("operation").asText()).isEqualTo("UPDATE");
            assertThat(updateEvent.path("before").path("effect").asText()).isEqualTo("ALLOW");
            assertThat(updateEvent.path("after").path("effect").asText()).isEqualTo("DENY");
            assertThat(updateEvent.path("after").path("enabled").asBoolean()).isFalse();
            assertThat(updateEvent.path("changedByUserId").asLong()).isEqualTo(adminId);

            HttpResult filteredPolicyHistory = request(
                    "GET",
                    "/api/policies/" + policyId
                            + "/audits?operation=UPDATE&changedByUsername=" + adminUsername + "&size=1",
                    adminToken,
                    null
            );
            assertStatus(filteredPolicyHistory, 200);
            assertThat(filteredPolicyHistory.body().path("content").size()).isEqualTo(1);
            assertThat(filteredPolicyHistory.body().path("totalElements").asLong()).isEqualTo(1L);
            assertThat(filteredPolicyHistory.body().path("content").get(0).path("operation").asText())
                    .isEqualTo("UPDATE");

            HttpResult deleted = request("DELETE", "/api/policies/" + policyId, adminToken, null);
            assertStatus(deleted, 204);
            var historyAfterDelete = policyAudits(adminToken, policyId);
            assertThat(historyAfterDelete).hasSize(3);
            JsonNode deleteEvent = historyAfterDelete.get(0);
            assertThat(deleteEvent.path("operation").asText()).isEqualTo("DELETE");
            assertThat(deleteEvent.path("before").path("effect").asText()).isEqualTo("DENY");
            assertThat(deleteEvent.path("after").isNull() || deleteEvent.path("after").isMissingNode()).isTrue();

            HttpResult deletedPolicyHistory = request(
                    "GET", "/api/policies/" + policyId + "/audits?operation=DELETE", adminToken, null
            );
            assertStatus(deletedPolicyHistory, 200);
            assertThat(deletedPolicyHistory.body().path("totalElements").asLong()).isEqualTo(1L);
            assertThat(deletedPolicyHistory.body().path("content").get(0).path("operation").asText())
                    .isEqualTo("DELETE");
        } finally {
            HttpResult cleanupDuplicate = request("DELETE", "/api/policies/" + duplicatePolicyId, adminToken, null);
            assertStatus(cleanupDuplicate, 204);
        }
    }

    private String login(String username, String password) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("username", username).put("password", password);
        HttpResult result = request("POST", "/api/auth/login", null, body);
        assertStatus(result, 200);
        String token = result.body().path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void registerUser(String username, String password) throws Exception {
        ObjectNode body = JSON.createObjectNode()
                .put("username", username)
                .put("password", password)
                .put("fullName", "Phase 11 Integration User");
        assertStatus(request("POST", "/api/auth/register", null, body), 201);
    }

    private JsonNode createDevice(String adminToken, String suffix) throws Exception {
        String deviceCode = "PHASE11-SENSOR-" + suffix.toUpperCase(Locale.ROOT);
        ObjectNode body = JSON.createObjectNode()
                .put("deviceCode", deviceCode)
                .put("deviceName", "Phase 11 audit integration device")
                .put("deviceType", "SENSOR")
                .put("ipAddress", "192.168.251.8")
                .put("mqttClientId", deviceCode);
        HttpResult result = request("POST", "/api/devices", adminToken, body);
        assertStatus(result, 201);
        return result.body();
    }

    private void patchStatus(String adminToken, long deviceId, String status, int expectedStatus) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("status", status);
        assertStatus(request("PATCH", "/api/devices/" + deviceId + "/status", adminToken, body), expectedStatus);
    }

    private ObjectNode policyBody(String name, String resource, String effect, boolean enabled) {
        return JSON.createObjectNode()
                .put("name", name)
                .put("subject", "SENSOR")
                .put("resource", resource)
                .put("action", "READ")
                .put("effect", effect)
                .put("enabled", enabled)
                .put("description", "Phase 11 audit integration policy");
    }

    private List<JsonNode> statusAudits(String adminToken, long deviceId) throws Exception {
        return pageContent(request("GET", "/api/devices/" + deviceId + "/status-audits", adminToken, null), 200);
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 11 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body) {
    }
}
