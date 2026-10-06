package com.yak.zerotrust.mqtt;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** Builds a TLS socket factory that trusts only the configured local MQTT CA. */
public final class MqttTlsSupport {

    private MqttTlsSupport() {
    }

    public static SSLSocketFactory createSocketFactory(String caFile) {
        if (caFile == null || caFile.isBlank()) {
            throw new IllegalArgumentException("MQTT CA certificate path is required");
        }

        try (InputStream certificateStream = Files.newInputStream(Path.of(caFile))) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificateStream);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("mqtt-local-ca", certificate);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the configured MQTT CA certificate", exception);
        }
    }
}
