package com.yak.zerotrust.exception;

public class InvalidDeviceOwnerException extends RuntimeException {

    public InvalidDeviceOwnerException() {
        super("Device owner must be an enabled USER account");
    }
}
