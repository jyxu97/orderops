package com.orderops.shared.exception;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String from, String to) {
        super("Invalid state transition: " + from + " -> " + to);
    }
}
