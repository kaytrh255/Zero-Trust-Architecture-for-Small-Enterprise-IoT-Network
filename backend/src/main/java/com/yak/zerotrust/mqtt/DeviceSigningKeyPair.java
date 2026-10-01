package com.yak.zerotrust.mqtt;

/** Base64url-encoded Ed25519 public/private key material created for a single device. */
public record DeviceSigningKeyPair(String publicKey, String privateKey) {
}
