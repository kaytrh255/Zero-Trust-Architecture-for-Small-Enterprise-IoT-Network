package com.yak.zerotrust.exception;

public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(Long id) {
        super("Policy " + id + " was not found");
    }
}
