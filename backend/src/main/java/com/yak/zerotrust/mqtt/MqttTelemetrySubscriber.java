package com.yak.zerotrust.mqtt;

import com.yak.zerotrust.service.TelemetryIngestionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MqttTelemetrySubscriber {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetrySubscriber.class);

    private final TelemetryIngestionService telemetryIngestionService;
    private final String brokerUrl;
    private final String username;
    private final String password;
    private final String topicFilter;
    private final String clientId;
    private final javax.net.ssl.SSLSocketFactory sslSocketFactory;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mqtt-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connectionPending = new AtomicBoolean(false);

    private volatile MqttAsyncClient client;

    public MqttTelemetrySubscriber(
            TelemetryIngestionService telemetryIngestionService,
            @Value("${mqtt.broker-url}") String brokerUrl,
            @Value("${mqtt.username:}") String username,
            @Value("${mqtt.password:}") String password,
            @Value("${mqtt.telemetry-topic}") String topicFilter,
            @Value("${mqtt.client-id}") String clientId,
            @Value("${mqtt.ca-file}") String caFile
    ) {
        this.telemetryIngestionService = telemetryIngestionService;
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.topicFilter = topicFilter;
        this.clientId = clientId;
        this.sslSocketFactory = MqttTlsSupport.createSocketFactory(caFile);
    }

    @PostConstruct
    void start() {
        try {
            client = new MqttAsyncClient(
                    brokerUrl,
                    clientId + "-" + UUID.randomUUID(),
                    new MemoryPersistence()
            );
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connectionPending.set(false);
                    log.info("Connected to MQTT broker{}", reconnect ? " after reconnect" : "");
                    subscribeToTelemetry();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    connectionPending.set(true);
                    log.warn("MQTT connection lost; automatic reconnect is pending");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        telemetryIngestionService.ingestMqttMessage(topic, message.getPayload());
                    } catch (RuntimeException exception) {
                        log.warn("Rejected MQTT telemetry message on topic {}: {}", topic, exception.getMessage());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // This subscriber does not publish messages.
                }
            });
            reconnectExecutor.scheduleWithFixedDelay(this::connectIfNeeded, 0, 5, TimeUnit.SECONDS);
        } catch (MqttException exception) {
            throw new IllegalStateException("Unable to initialize MQTT telemetry subscriber", exception);
        }
    }

    private void connectIfNeeded() {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || activeClient.isConnected() || !connectionPending.compareAndSet(false, true)) {
            return;
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(30);
        options.setSocketFactory(sslSocketFactory);
        options.setHttpsHostnameVerificationEnabled(true);
        if (!username.isBlank()) {
            options.setUserName(username);
        }
        if (!password.isBlank()) {
            options.setPassword(password.toCharArray());
        }

        try {
            activeClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken) {
                    connectionPending.set(false);
                }

                @Override
                public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken, Throwable exception) {
                    connectionPending.set(false);
                    log.warn("MQTT broker connection failed; retrying in five seconds");
                }
            });
        } catch (MqttException exception) {
            connectionPending.set(false);
            log.warn("MQTT broker connection attempt failed; retrying in five seconds");
        }
    }

    private void subscribeToTelemetry() {
        MqttAsyncClient activeClient = client;
        if (activeClient == null || !activeClient.isConnected()) {
            return;
        }
        try {
            activeClient.subscribe(topicFilter, 1, null, new IMqttActionListener() {
                @Override
                public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken) {
                    log.info("Subscribed to MQTT telemetry topic {}", topicFilter);
                }

                @Override
                public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken, Throwable exception) {
                    log.error("Unable to subscribe to MQTT telemetry topic {}", topicFilter, exception);
                }
            });
        } catch (MqttException exception) {
            log.error("Unable to subscribe to MQTT telemetry topic {}", topicFilter, exception);
        }
    }

    @PreDestroy
    void stop() {
        reconnectExecutor.shutdownNow();
        MqttAsyncClient activeClient = client;
        if (activeClient == null) {
            return;
        }
        try {
            if (activeClient.isConnected()) {
                activeClient.disconnect().waitForCompletion(2_000);
            }
            activeClient.close();
        } catch (MqttException exception) {
            log.debug("MQTT subscriber shutdown did not complete cleanly", exception);
        }
    }
}
