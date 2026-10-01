package com.yak.zerotrust.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yak.zerotrust.dto.SignedMqttTelemetryEnvelope;
import com.yak.zerotrust.testsupport.MqttTestMessageSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttMessageSignatureServiceTest {

    private final MqttMessageSignatureService service = new MqttMessageSignatureService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void verifiesExactPayloadBytesAndRejectsTamperingAndOtherDeviceKeys() throws Exception {
        DeviceSigningKeyPair deviceKeys = service.generateKeyPair();
        DeviceSigningKeyPair otherDeviceKeys = service.generateKeyPair();
        byte[] signedPayload = "{\"sequence\":1,\"metric\":\"temperature\",\"value\":22.5,\"unit\":\"C\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] envelopeBytes = MqttTestMessageSigner.envelope(signedPayload, deviceKeys.privateKey());
        SignedMqttTelemetryEnvelope envelope = objectMapper.readValue(
                envelopeBytes,
                SignedMqttTelemetryEnvelope.class
        );
        byte[] decodedPayload = service.decodePayload(envelope.payload());

        assertThat(decodedPayload).containsExactly(signedPayload);
        assertThat(service.verify(decodedPayload, envelope.signature(), deviceKeys.publicKey())).isTrue();
        assertThat(service.verify(decodedPayload, envelope.signature(), otherDeviceKeys.publicKey())).isFalse();

        byte[] modifiedPayload = "{\"sequence\":2,\"metric\":\"temperature\",\"value\":22.5,\"unit\":\"C\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(service.verify(modifiedPayload, envelope.signature(), deviceKeys.publicKey())).isFalse();
    }

    @Test
    void rejectsNonCanonicalBase64UrlAndMalformedSignatures() {
        assertThat(service.generateKeyPair().publicKey()).matches("[A-Za-z0-9_-]{59}");
        assertThat(service.generateKeyPair().privateKey()).matches("[A-Za-z0-9_-]+");
        assertThatThrownBy(() -> service.decodePayload(Base64.getUrlEncoder().encodeToString(new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpadded base64url");
        assertThat(service.verify(new byte[]{1}, "not-a-signature", "not-a-public-key")).isFalse();
    }
}
