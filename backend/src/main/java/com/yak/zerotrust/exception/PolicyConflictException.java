package com.yak.zerotrust.exception;

public class PolicyConflictException extends RuntimeException {

    public PolicyConflictException() {
        super("A policy with this name already exists");
    }
}
