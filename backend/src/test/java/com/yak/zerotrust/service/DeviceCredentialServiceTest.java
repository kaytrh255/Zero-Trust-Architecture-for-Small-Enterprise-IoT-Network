package com.yak.zerotrust.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCredentialServiceTest {

    @Test
    void issuesDistinctHighEntropyMqttPasswords() {
        DeviceCredentialService service = new DeviceCredentialService();

        String first = service.issueMqttPassword();
        String second = service.issueMqttPassword();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(first).isNotEqualTo(second);
    }
}
