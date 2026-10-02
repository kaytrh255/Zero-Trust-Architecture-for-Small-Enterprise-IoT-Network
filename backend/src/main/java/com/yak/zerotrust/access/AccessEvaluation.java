package com.yak.zerotrust.access;

import com.yak.zerotrust.entity.Device;

public record AccessEvaluation(AccessDecision decision, Device device) {
}
