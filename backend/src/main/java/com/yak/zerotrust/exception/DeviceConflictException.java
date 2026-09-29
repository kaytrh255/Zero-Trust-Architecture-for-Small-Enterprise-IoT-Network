package com.yak.zerotrust.exception;

public class DeviceConflictException extends RuntimeException {

    public DeviceConflictException() {
        super("Device code or MQTT client ID is already registered");
    }
}
