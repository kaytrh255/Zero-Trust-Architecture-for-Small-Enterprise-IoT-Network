package com.yak.zerotrust.exception;

public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException(Long id) {
        super("Device " + id + " was not found");
    }
}
