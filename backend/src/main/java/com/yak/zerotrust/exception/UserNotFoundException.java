package com.yak.zerotrust.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("Authenticated owner account was not found");
    }
}
