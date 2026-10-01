package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yak.zerotrust.mqtt.MqttTlsSupport;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end checks against an already running local Docker Compose stack. */
@EnabledIfEnvironmentVariable(named = "PHASE10_INTEGRATION", matches = "true")
class Phase10ComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ASYNC_WAIT = Duration.ofSeconds(12);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl = environment("PHASE10_BASE_URL", "http://127.0.0.1:8080");
    private final String brokerUri = environment(
            "PHASE10_MQTT_BROKER_URI",
            "ssl://127.0.0.1:" + environment("MQTT_PORT", "8883")
    );
    private final Path caFile = Path.of(environment("PHASE10_MQTT_CA_FILE", "../mosquitto/tls/ca.crt"))
            .toAbsolutePath();

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void composeStackEnforcesTlsBrokerAclStatusPolicyReplayAndProtectedResource() throws Exception {
        String adminUsername = environment("PHASE10_ADMIN_USERNAME", environment("ADMIN_USERNAME", "admin"))
                .trim()
                .toLowerCase(Locale.ROOT);
        String adminToken = login(adminUsername, requiredEnvironment("PHASE10_ADMIN_PASSWORD", "ADMIN_PASSWORD"));
        HttpResult health = request("GET", "/actuator/health", null, null);
        assertStatus(health, 200);
        assertThat(health.body().path("status").asText()).isEqualTo("UP");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String sensorCode = "PHASE10-SENSOR-" + suffix;
        String actuatorCode = "PHASE10-ACTUATOR-" + suffix;
        String userSuffix = suffix.toLowerCase();
        String ownerUsername = "phase10-owner-" + userSuffix;
        String nonOwnerUsername = "phase10-outsider-" + userSuffix;
        String ownerPassword = "Phase10!" + userSuffix + "Aa";
        String nonOwnerPassword = "Phase10!outsider-" + userSuffix + "Aa";
        registerUser(ownerUsername, ownerPassword);
        registerUser(nonOwnerUsername, nonOwnerPassword);
        String ownerToken = login(ownerUsername, ownerPassword);
        String nonOwnerToken = login(nonOwnerUsername, nonOwnerPassword);

        DeviceCredentials originalSensor = createDevice(adminToken, sensorCode, "SENSOR");
        JsonNode initialDevice = getDevice(adminToken, originalSensor.deviceId());
        long adminId = initialDevice.path("ownerId").asLong();
        assertThat(adminId).isPositive();

        HttpResult invalidOwnerChange = request(
                "PATCH",
                "/api/devices/" + originalSensor.deviceId() + "/owner",
                adminToken,
                JSON.createObjectNode().put("ownerUsername", adminUsername)
        );
        assertStatus(invalidOwnerChange, 400);
        assertThat(getDevice(adminToken, originalSensor.deviceId()).path("ownerUsername").asText())
                .isEqualTo(adminUsername);

        HttpResult forbiddenOwnerChange = request(
                "PATCH",
                "/api/devices/" + originalSensor.deviceId() + "/owner",
                ownerToken,
                JSON.createObjectNode().put("ownerUsername", nonOwnerUsername)
        );
        assertStatus(forbiddenOwnerChange, 403);
        assertThat(ownershipAudits(adminToken, originalSensor.deviceId())).isEmpty();

        DeviceCredentials sensor = rotateCredentials(adminToken, originalSensor);
        transferOwnership(adminToken, sensor.deviceId(), ownerUsername);
        JsonNode transferredDevice = getDevice(adminToken, sensor.deviceId());
        assertThat(transferredDevice.path("ownerUsername").asText()).isEqualTo(ownerUsername);
        long newOwnerId = transferredDevice.path("ownerId").asLong();

        List<JsonNode> ownershipHistory = ownershipAudits(adminToken, sensor.deviceId());
        assertThat(ownershipHistory).hasSize(1);
        JsonNode transfer = ownershipHistory.getFirst();
        assertThat(transfer.path("deviceCode").asText()).isEqualTo(sensorCode);
        assertThat(transfer.path("previousOwnerId").asLong()).isEqualTo(adminId);
        assertThat(transfer.path("previousOwnerUsername").asText()).isEqualTo(adminUsername);
        assertThat(transfer.path("newOwnerId").asLong()).isEqualTo(newOwnerId);
        assertThat(transfer.path("newOwnerUsername").asText()).isEqualTo(ownerUsername);
        assertThat(transfer.path("changedByUserId").asLong()).isEqualTo(adminId);
        assertThat(transfer.path("changedByUsername").asText()).isEqualTo(adminUsername);
        assertThat(transfer.path("changedAt").asText()).isNotBlank();

        HttpResult forbiddenHistory = request(
                "GET", "/api/devices/" + sensor.deviceId() + "/ownership-audits", ownerToken, null
        );
        assertStatus(forbiddenHistory, 403);

        transferOwnership(adminToken, sensor.deviceId(), ownerUsername);
        assertThat(ownershipAudits(adminToken, sensor.deviceId())).hasSize(1);
        assertConnectionRejected(new DeviceCredentials(
                originalSensor.deviceId(),
                originalSensor.deviceCode(),
                originalSensor.clientId(),
                originalSensor.username(),
                originalSensor.password()
        ), originalSensor.clientId(), true, "A rotated MQTT password must stop authenticating");

        assertConnectionRejected(sensor, sensor.clientId(), false,
                "The local CA must not be accepted by the JVM default trust store");
        assertConnectionRejected(sensor, sensor.clientId() + "-wrong", true,
                "The broker must enforce the registered MQTT client ID");

        publish(sensor, sensorCode, 1);
        awaitAudit(adminToken, sensorCode, "POLICY_ALLOW", 1L);
        awaitTelemetry(adminToken, sensorCode, 1L, true);
        String lastSeenAfterAllow = getDevice(adminToken, sensor.deviceId()).path("lastSeenAt").asText();
        assertThat(lastSeenAfterAllow).isNotBlank();

        String otherDeviceCode = "PHASE10-OTHER-" + suffix;
        tryPublish(sensor, otherDeviceCode, 1);
        assertNoAuditForDevice(adminToken, otherDeviceCode);
        assertNoTelemetry(adminToken, otherDeviceCode, null);

        publish(sensor, sensorCode, 1);
        awaitAudit(adminToken, sensorCode, "REPLAYED_MESSAGE", 1L);
        assertTelemetryCount(adminToken, sensorCode, 1L, 1);
        assertThat(getDevice(adminToken, sensor.deviceId()).path("lastSeenAt").asText())
                .isEqualTo(lastSeenAfterAllow);

        patchStatus(adminToken, sensor.deviceId(), "BLOCKED");
        publish(sensor, sensorCode, 2);
        awaitAudit(adminToken, sensorCode, "DEVICE_NOT_ACTIVE", 2L);
        assertNoTelemetry(adminToken, sensorCode, 2L);
        assertThat(getDevice(adminToken, sensor.deviceId()).path("lastSeenAt").asText())
                .isEqualTo(lastSeenAfterAllow);
        HttpResult blockedResource = request(
                "GET", "/api/resources/devices/" + sensorCode + "/telemetry", ownerToken, null
        );
        assertStatus(blockedResource, 403);
        assertThat(blockedResource.body().path("accessDecision").path("reason").asText())
                .isEqualTo("DEVICE_NOT_ACTIVE");
        assertThat(blockedResource.body().path("telemetry").size()).isZero();
        patchStatus(adminToken, sensor.deviceId(), "ACTIVE");

        long denyPolicyId = createTelemetryDenyPolicy(adminToken, suffix);
        try {
            publish(sensor, sensorCode, 2);
            awaitAudit(adminToken, sensorCode, "EXPLICIT_DENY", 2L);
            assertNoTelemetry(adminToken, sensorCode, 2L);
            assertThat(getDevice(adminToken, sensor.deviceId()).path("lastSeenAt").asText())
                    .isEqualTo(lastSeenAfterAllow);
        } finally {
            HttpResult deletePolicy = request("DELETE", "/api/policies/" + denyPolicyId, adminToken, null);
            assertStatus(deletePolicy, 204);
        }

        // Reusing sequence 2 proves status and policy DENYs did not consume the high-water mark.
        publish(sensor, sensorCode, 2);
        awaitAudit(adminToken, sensorCode, "POLICY_ALLOW", 2L);
        awaitTelemetry(adminToken, sensorCode, 2L, true);
        publish(sensor, sensorCode, 2);
        awaitAudit(adminToken, sensorCode, "REPLAYED_MESSAGE", 2L);
        assertTelemetryCount(adminToken, sensorCode, 2L, 1);

        DeviceCredentials actuator = createDevice(adminToken, actuatorCode, "ACTUATOR");
        publish(actuator, actuatorCode, 1);
        awaitAudit(adminToken, actuatorCode, "NO_MATCHING_POLICY", 1L);
        assertNoTelemetry(adminToken, actuatorCode, 1L);

        HttpResult genericNonOwnerCheck = request(
                "POST",
                "/api/access/check",
                nonOwnerToken,
                JSON.createObjectNode()
                        .put("deviceCode", sensorCode)
                        .put("resource", "sensor-data")
                        .put("action", "READ")
        );
        assertStatus(genericNonOwnerCheck, 200);
        assertThat(genericNonOwnerCheck.body().path("decision").asText()).isEqualTo("ALLOW");
        assertThat(genericNonOwnerCheck.body().path("reason").asText()).isEqualTo("POLICY_ALLOW");

        HttpResult defaultDeny = request(
                "POST",
                "/api/access/check",
                ownerToken,
                JSON.createObjectNode()
                        .put("deviceCode", sensorCode)
                        .put("resource", "phase10-no-matching-resource")
                        .put("action", "READ")
        );
        assertStatus(defaultDeny, 200);
        assertThat(defaultDeny.body().path("decision").asText()).isEqualTo("DENY");
        assertThat(defaultDeny.body().path("reason").asText()).isEqualTo("NO_MATCHING_POLICY");

        long protectedReadDenyPolicyId = createProtectedReadDenyPolicy(adminToken, suffix);
        try {
            HttpResult explicitReadDeny = request(
                    "GET", "/api/resources/devices/" + sensorCode + "/telemetry", ownerToken, null
            );
            assertStatus(explicitReadDeny, 403);
            assertThat(explicitReadDeny.body().path("accessDecision").path("reason").asText())
                    .isEqualTo("EXPLICIT_DENY");
            assertThat(explicitReadDeny.body().path("telemetry").size()).isZero();
            assertThat(explicitReadDeny.body().path("accessDecision").path("auditId").isNumber()).isTrue();
        } finally {
            HttpResult deletePolicy = request(
                    "DELETE", "/api/policies/" + protectedReadDenyPolicyId, adminToken, null
            );
            assertStatus(deletePolicy, 204);
        }

        HttpResult protectedRead = request(
                "GET", "/api/resources/devices/" + sensorCode + "/telemetry", ownerToken, null
        );
        assertStatus(protectedRead, 200);
        assertThat(protectedRead.body().path("accessDecision").path("decision").asText()).isEqualTo("ALLOW");
        assertThat(protectedRead.body().path("telemetry").size()).isEqualTo(2);
        assertThat(protectedRead.body().path("accessDecision").path("auditId").isNumber()).isTrue();

        HttpResult nonOwnerRead = request(
                "GET", "/api/resources/devices/" + sensorCode + "/telemetry", nonOwnerToken, null
        );
        assertStatus(nonOwnerRead, 403);
        assertThat(nonOwnerRead.body().path("accessDecision").path("reason").asText())
                .isEqualTo("DEVICE_NOT_OWNED");
        assertThat(nonOwnerRead.body().path("accessDecision").path("matchedPolicyId").isNumber()).isTrue();
        long ownershipAuditId = nonOwnerRead.body().path("accessDecision").path("auditId").asLong();
        assertThat(ownershipAuditId).isPositive();
        assertThat(nonOwnerRead.body().path("telemetry").size()).isZero();
        assertThat(audits(adminToken)).anySatisfy(row -> {
            assertThat(row.path("id").asLong()).isEqualTo(ownershipAuditId);
            assertThat(row.path("reason").asText()).isEqualTo("DEVICE_NOT_OWNED");
            assertThat(row.path("deviceCode").asText()).isEqualTo(sensorCode);
        });

        HttpResult protectedDefaultDeny = request(
                "GET", "/api/resources/devices/CAMERA-001/telemetry", ownerToken, null
        );
        assertStatus(protectedDefaultDeny, 403);
        assertThat(protectedDefaultDeny.body().path("accessDecision").path("reason").asText())
                .isEqualTo("NO_MATCHING_POLICY");
        assertThat(protectedDefaultDeny.body().path("telemetry").size()).isZero();
    }

    private String login(String username, String password) throws Exception {
        JsonNode body = JSON.createObjectNode().put("username", username).put("password", password);
        HttpResult result = request("POST", "/api/auth/login", null, body);
        assertStatus(result, 200);
        String token = result.body().path("accessToken").asText();
        assertThat(token).as("login accessToken").isNotBlank();
        return token;
    }

    private void registerUser(String username, String password) throws Exception {
        JsonNode body = JSON.createObjectNode()
                .put("username", username)
                .put("password", password)
                .put("fullName", "Phase 10 Integration User");
        assertStatus(request("POST", "/api/auth/register", null, body), 201);
    }

    private DeviceCredentials createDevice(String adminToken, String deviceCode, String type) throws Exception {
        JsonNode body = JSON.createObjectNode()
                .put("deviceCode", deviceCode)
                .put("deviceName", "Phase 10 " + type + " integration device")
                .put("deviceType", type)
                .put("ipAddress", "192.168.250.8")
                .put("mqttClientId", deviceCode);
        HttpResult result = request("POST", "/api/devices", adminToken, body);
        assertStatus(result, 201);
        JsonNode response = result.body();
        return new DeviceCredentials(
                response.path("device").path("id").asLong(),
                response.path("device").path("deviceCode").asText(),
                response.path("device").path("mqttClientId").asText(),
                response.path("mqttUsername").asText(),
                response.path("mqttPassword").asText()
        );
    }

    private void transferOwnership(String adminToken, long deviceId, String ownerUsername) throws Exception {
        JsonNode body = JSON.createObjectNode().put("ownerUsername", " " + ownerUsername.toUpperCase(Locale.ROOT) + " ");
        HttpResult result = request("PATCH", "/api/devices/" + deviceId + "/owner", adminToken, body);
        assertStatus(result, 200);
        assertThat(result.body().path("ownerUsername").asText()).isEqualTo(ownerUsername);
    }

    private DeviceCredentials rotateCredentials(String adminToken, DeviceCredentials existing) throws Exception {
        HttpResult result = request(
                "POST", "/api/devices/" + existing.deviceId() + "/credentials/rotate", adminToken, null
        );
        assertStatus(result, 200);
        return new DeviceCredentials(
                existing.deviceId(),
                existing.deviceCode(),
                result.body().path("device").path("mqttClientId").asText(),
                result.body().path("mqttUsername").asText(),
                result.body().path("mqttPassword").asText()
        );
    }

    private long createTelemetryDenyPolicy(String adminToken, String suffix) throws Exception {
        JsonNode body = JSON.createObjectNode()
                .put("name", "Phase 10 telemetry deny " + suffix)
                .put("subject", "SENSOR")
                .put("resource", "device-telemetry")
                .put("action", "WRITE")
                .put("effect", "DENY")
                .put("enabled", true)
                .put("description", "Temporary Phase 10 integration-test policy");
        HttpResult result = request("POST", "/api/policies", adminToken, body);
        assertStatus(result, 201);
        return result.body().path("id").asLong();
    }

    private long createProtectedReadDenyPolicy(String adminToken, String suffix) throws Exception {
        JsonNode body = JSON.createObjectNode()
                .put("name", "Phase 10 protected read deny " + suffix)
                .put("subject", "SENSOR")
                .put("resource", "sensor-data")
                .put("action", "READ")
                .put("effect", "DENY")
                .put("enabled", true)
                .put("description", "Temporary Phase 10 protected-resource DENY policy");
        HttpResult result = request("POST", "/api/policies", adminToken, body);
        assertStatus(result, 201);
        return result.body().path("id").asLong();
    }

    private void patchStatus(String adminToken, long deviceId, String status) throws Exception {
        JsonNode body = JSON.createObjectNode().put("status", status);
        assertStatus(request("PATCH", "/api/devices/" + deviceId + "/status", adminToken, body), 200);
    }

    private void publish(DeviceCredentials credentials, String topicDeviceCode, long sequence) throws Exception {
        MqttClient client = new MqttClient(brokerUri, credentials.clientId(), new MemoryPersistence());
        try {
            client.connect(connectOptions(credentials, true));
            ObjectNode payload = JSON.createObjectNode()
                    .put("sequence", sequence)
                    .put("metric", "phase10")
                    .put("value", 22.5)
                    .put("unit", "C");
            client.publish(
                    "iot/telemetry/" + topicDeviceCode,
                    JSON.writeValueAsBytes(payload),
                    1,
                    false
            );
        } finally {
            closeQuietly(client);
        }
    }

    private void tryPublish(DeviceCredentials credentials, String topicDeviceCode, long sequence) throws Exception {
        MqttClient client = null;
        try {
            client = new MqttClient(brokerUri, credentials.clientId(), new MemoryPersistence());
            client.connect(connectOptions(credentials, true));
            ObjectNode payload = JSON.createObjectNode()
                    .put("sequence", sequence)
                    .put("metric", "phase10")
                    .put("value", 22.5)
                    .put("unit", "C");
            client.publish(
                    "iot/telemetry/" + topicDeviceCode,
                    JSON.writeValueAsBytes(payload),
                    1,
                    false
            );
        } catch (MqttException expectedBrokerRejection) {
            // QoS 1 brokers may reject an ACL violation during publish or silently drop it.
        } finally {
            if (client != null) {
                closeQuietly(client);
            }
        }
    }

    private void assertConnectionRejected(
            DeviceCredentials credentials,
            String clientId,
            boolean trustLocalCa,
            String reason
    ) throws Exception {
        MqttClient client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
        try {
            assertThatThrownBy(() -> client.connect(connectOptions(credentials, trustLocalCa)))
                    .as(reason)
                    .isInstanceOf(MqttException.class);
        } finally {
            closeQuietly(client);
        }
    }

    private MqttConnectOptions connectOptions(DeviceCredentials credentials, boolean trustLocalCa) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(credentials.username());
        options.setPassword(credentials.password().toCharArray());
        options.setCleanSession(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(10);
        options.setHttpsHostnameVerificationEnabled(true);
        if (trustLocalCa) {
            options.setSocketFactory(MqttTlsSupport.createSocketFactory(caFile.toString()));
        }
        return options;
    }

    private void awaitAudit(String adminToken, String deviceCode, String reason, Long sequence) throws Exception {
        Predicate<JsonNode> expectedAudit = matchesAudit(deviceCode, reason, sequence);
        long deadline = System.nanoTime() + ASYNC_WAIT.toNanos();
        List<JsonNode> observedForDevice = List.of();
        do {
            observedForDevice = audits(adminToken).stream()
                    .filter(row -> row.path("deviceCode").asText().equals(deviceCode))
                    .toList();
            if (observedForDevice.stream().anyMatch(expectedAudit)) {
                return;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for audit " + reason + " for " + deviceCode
                + " sequence " + sequence + "; observed audit rows: " + observedForDevice);
    }

    private void awaitTelemetry(String adminToken, String deviceCode, Long sequence, boolean expected) throws Exception {
        await(() -> {
            try {
                boolean present = telemetry(adminToken).stream().anyMatch(row ->
                        row.path("deviceCode").asText().equals(deviceCode)
                                && row.path("deviceSequence").asLong(-1) == sequence
                );
                return present == expected;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, "telemetry " + (expected ? "for" : "absence of") + " " + deviceCode + " sequence " + sequence);
    }

    private void assertNoTelemetry(String adminToken, String deviceCode, Long sequence) throws Exception {
        List<JsonNode> records = telemetry(adminToken).stream()
                .filter(row -> row.path("deviceCode").asText().equals(deviceCode))
                .filter(row -> sequence == null || row.path("deviceSequence").asLong(-1) == sequence)
                .toList();
        assertThat(records).as("telemetry records for %s sequence %s", deviceCode, sequence).isEmpty();
    }

    private void assertTelemetryCount(String adminToken, String deviceCode, Long sequence, int count) throws Exception {
        long actual = telemetry(adminToken).stream()
                .filter(row -> row.path("deviceCode").asText().equals(deviceCode))
                .filter(row -> sequence == null || row.path("deviceSequence").asLong(-1) == sequence)
                .count();
        assertThat(actual).as("telemetry rows for %s sequence %s", deviceCode, sequence).isEqualTo(count);
    }

    private void assertNoAuditForDevice(String adminToken, String deviceCode) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            boolean found = audits(adminToken).stream()
                    .anyMatch(row -> row.path("deviceCode").asText().equals(deviceCode));
            assertThat(found).as("broker ACL rejection must happen before backend audit evaluation").isFalse();
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
    }

    private JsonNode getDevice(String adminToken, long deviceId) throws Exception {
        HttpResult result = request("GET", "/api/devices/" + deviceId, adminToken, null);
        assertStatus(result, 200);
        return result.body();
    }

    private List<JsonNode> ownershipAudits(String adminToken, long deviceId) throws Exception {
        return array(request("GET", "/api/devices/" + deviceId + "/ownership-audits", adminToken, null), 200);
    }

    private List<JsonNode> audits(String adminToken) throws Exception {
        return array(request("GET", "/api/access/audits", adminToken, null), 200);
    }

    private List<JsonNode> telemetry(String adminToken) throws Exception {
        return array(request("GET", "/api/telemetry", adminToken, null), 200);
    }

    private List<JsonNode> array(HttpResult result, int expectedStatus) {
        assertStatus(result, expectedStatus);
        List<JsonNode> values = new ArrayList<>();
        result.body().forEach(values::add);
        return values;
    }

    private Predicate<JsonNode> matchesAudit(String deviceCode, String reason, Long sequence) {
        return row -> row.path("deviceCode").asText().equals(deviceCode)
                && row.path("reason").asText().equals(reason)
                && (sequence == null || row.path("messageSequence").asLong(-1) == sequence);
    }

    private void await(CheckedCondition condition, String description) throws Exception {
        long deadline = System.nanoTime() + ASYNC_WAIT.toNanos();
        do {
            if (condition.isTrue()) {
                return;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
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

    private static void closeQuietly(MqttClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
            // A failed connect or broker-side rejection may leave the client already closed.
        }
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
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 10 integration checks");
        }
        return value;
    }

    private record DeviceCredentials(long deviceId, String deviceCode, String clientId, String username, String password) {
    }

    private record HttpResult(int status, JsonNode body) {
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean isTrue() throws Exception;
    }
}
