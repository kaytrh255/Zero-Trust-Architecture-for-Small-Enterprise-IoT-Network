package com.yak.zerotrust.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Provisions broker clients through Mosquitto Dynamic Security over verified TLS. */
@Component
public class MqttDynamicSecurityService {

    private static final String COMMAND_TOPIC = "$CONTROL/dynamic-security/v1";
    private static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";
    private static final String DEVICE_ROLE = "zt-device-publisher";
    private static final int COMMAND_TIMEOUT_SECONDS = 10;

    private final String brokerUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final String caFile;
    private final String clientIdPrefix;
    private final ObjectMapper objectMapper;
    private final Object commandLock = new Object();

    private volatile MqttClient client;
    private volatile CompletableFuture<String> pendingResponse;

    public MqttDynamicSecurityService(
            @Value("${mqtt.broker-url}") String brokerUrl,
            @Value("${mqtt.dynamic-security.username}") String adminUsername,
            @Value("${mqtt.dynamic-security.password}") String adminPassword,
            @Value("${mqtt.ca-file}") String caFile,
            @Value("${mqtt.client-id}") String clientIdPrefix,
            ObjectMapper objectMapper
    ) {
        this.brokerUrl = brokerUrl;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.caFile = caFile;
        this.clientIdPrefix = clientIdPrefix;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        try {
            client = new MqttClient(
                    brokerUrl,
                    clientIdPrefix + "-dynsec-" + UUID.randomUUID(),
                    new MemoryPersistence()
            );
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    CompletableFuture<String> current = pendingResponse;
                    if (current != null) {
                        current.completeExceptionally(cause);
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    if (!RESPONSE_TOPIC.equals(topic)) {
                        return;
                    }
                    CompletableFuture<String> current = pendingResponse;
                    if (current != null) {
                        current.complete(new String(message.getPayload(), StandardCharsets.UTF_8));
                    }
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                    // Dynamic Security commands are handled through the response topic.
                }
            });
            connectAndSubscribe();
        } catch (MqttException exception) {
            throw new IllegalStateException("Unable to connect to the MQTT Dynamic Security API", exception);
        }
    }

    public void provisionDevice(String username, String mqttClientId, String password, boolean enabled) {
        synchronized (commandLock) {
            JsonNode existingClient = findClient(username);
            ObjectNode command = objectMapper.createObjectNode()
                    .put("command", existingClient == null ? "createClient" : "modifyClient")
                    .put("username", username)
                    .put("password", password)
                    .put("clientid", mqttClientId);
            ArrayNode roles = command.putArray("roles");
            roles.addObject().put("rolename", DEVICE_ROLE).put("priority", 10);
            sendCommand(command);
            ensureEnabledState(username, enabled);
        }
    }

    public void setDeviceEnabled(String username, boolean enabled) {
        synchronized (commandLock) {
            ensureEnabledState(username, enabled);
        }
    }

    private void ensureEnabledState(String username, boolean enabled) {
        JsonNode existingClient = findClient(username);
        if (existingClient == null) {
            // A legacy device has no broker credential yet; it cannot connect until provisioned.
            return;
        }

        boolean currentlyDisabled = existingClient.path("data").path("client").path("disabled").asBoolean(false);
        if (currentlyDisabled == enabled) {
            ObjectNode command = objectMapper.createObjectNode()
                    .put("command", enabled ? "enableClient" : "disableClient")
                    .put("username", username);
            sendCommand(command);
        }
    }

    private JsonNode findClient(String username) {
        ObjectNode command = objectMapper.createObjectNode()
                .put("command", "getClient")
                .put("username", username);
        JsonNode result = sendCommand(command, true);
        String error = result.path("error").asText("");
        if (!error.isBlank()) {
            String normalizedError = error.toLowerCase(Locale.ROOT);
            if (normalizedError.contains("not found") || normalizedError.contains("does not exist")) {
                return null;
            }
            throw new IllegalStateException("Mosquitto Dynamic Security getClient failed: " + error);
        }
        return result;
    }

    private JsonNode sendCommand(ObjectNode command) {
        return sendCommand(command, false);
    }

    private JsonNode sendCommand(ObjectNode command, boolean allowResponseError) {
        synchronized (commandLock) {
            ensureConnected();
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            pendingResponse = responseFuture;
            try {
                ObjectNode request = objectMapper.createObjectNode();
                ArrayNode commands = request.putArray("commands");
                commands.add(command);
                byte[] requestBytes = objectMapper.writeValueAsBytes(request);
                client.publish(COMMAND_TOPIC, requestBytes, 1, false);

                String responseText = responseFuture.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                JsonNode response = firstResponse(objectMapper.readTree(responseText));
                String error = response.path("error").asText("");
                if (!allowResponseError && !error.isBlank()) {
                    throw new IllegalStateException(
                            "Mosquitto Dynamic Security command "
                                    + command.path("command").asText() + " failed: " + error
                    );
                }
                return response;
            } catch (TimeoutException exception) {
                disconnectAfterTimeout();
                throw new IllegalStateException("Timed out waiting for Mosquitto Dynamic Security", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Mosquitto Dynamic Security", exception);
            } catch (JsonProcessingException | MqttException exception) {
                throw new IllegalStateException("Unable to execute a Mosquitto Dynamic Security command", exception);
            } catch (java.util.concurrent.ExecutionException exception) {
                throw new IllegalStateException("MQTT connection failed during a Dynamic Security command", exception.getCause());
            } finally {
                if (pendingResponse == responseFuture) {
                    pendingResponse = null;
                }
            }
        }
    }

    private JsonNode firstResponse(JsonNode envelope) {
        JsonNode responses = envelope.path("responses");
        if (!responses.isArray() || responses.isEmpty()) {
            throw new IllegalStateException("Mosquitto Dynamic Security returned no command response");
        }
        return responses.get(0);
    }

    private void ensureConnected() {
        MqttClient activeClient = client;
        if (activeClient == null) {
            throw new IllegalStateException("MQTT Dynamic Security client has not started");
        }
        if (activeClient.isConnected()) {
            return;
        }
        try {
            connectAndSubscribe();
        } catch (MqttException exception) {
            throw new IllegalStateException("Unable to reconnect to the MQTT Dynamic Security API", exception);
        }
    }

    private void connectAndSubscribe() throws MqttException {
        MqttClient activeClient = client;
        if (activeClient == null || activeClient.isConnected()) {
            return;
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(adminUsername);
        options.setPassword(adminPassword.toCharArray());
        options.setSocketFactory(MqttTlsSupport.createSocketFactory(caFile));
        options.setHttpsHostnameVerificationEnabled(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(30);
        activeClient.connect(options);
        activeClient.subscribe(RESPONSE_TOPIC, 1);
    }

    private void disconnectAfterTimeout() {
        MqttClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return;
        }
        try {
            activeClient.disconnect();
        } catch (MqttException ignored) {
            // A timeout already fails the provisioning operation; the next call reconnects.
        }
    }

    @PreDestroy
    void stop() {
        MqttClient activeClient = client;
        if (activeClient == null) {
            return;
        }
        try {
            if (activeClient.isConnected()) {
                activeClient.disconnect();
            }
            activeClient.close();
        } catch (MqttException ignored) {
            // Best-effort shutdown.
        }
    }
}
