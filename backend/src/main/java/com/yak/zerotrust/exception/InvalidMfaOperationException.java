package com.yak.zerotrust.exception;

public class InvalidMfaOperationException extends RuntimeException {

    public InvalidMfaOperationException(String message) {
        super(message);
    }
}
