package com.yak.zerotrust.dto;

/** MQTT wire envelope; signature covers the decoded payload bytes exactly. */
public record SignedMqttTelemetryEnvelope(String payload, String signature) {
}
