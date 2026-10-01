package com.yak.zerotrust.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Builds protocol-correct MQTT test envelopes using a provisioned device private key. */
public final class MqttTestMessageSigner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private MqttTestMessageSigner() {
    }

    public static byte[] envelope(byte[] payload, String encodedPrivateKey) {
        return envelope(payload, payload, encodedPrivateKey);
    }

    /** Signs {@code signedBytes} but transports {@code transmittedBytes}, enabling tamper tests. */
    public static byte[] envelope(byte[] signedBytes, byte[] transmittedBytes, String encodedPrivateKey) {
        try {
            byte[] privateKeyBytes = Base64.getUrlDecoder().decode(encodedPrivateKey);
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signedBytes);
            String signature = BASE64URL.encodeToString(signer.sign());

            ObjectNode envelope = JSON.createObjectNode()
                    .put("payload", BASE64URL.encodeToString(transmittedBytes))
                    .put("signature", signature);
            return JSON.writeValueAsBytes(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to prepare signed MQTT integration-test payload", exception);
        }
    }
}
