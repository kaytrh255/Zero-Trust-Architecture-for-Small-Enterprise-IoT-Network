package com.yak.zerotrust.security;

public record MfaChallengeClaims(String username, String challengeId) {
}
