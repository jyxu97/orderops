package com.orderops.api.exception;

public class RedriveConflictException extends RuntimeException {

    public RedriveConflictException(Long messagesMoved, Long messagesToMove) {
        super(buildMessage(messagesMoved, messagesToMove));
    }

    private static String buildMessage(Long moved, Long toMove) {
        if (moved == null || toMove == null) {
            return "A dead-letter redrive is already running";
        }
        return "A dead-letter redrive is already running (%d of %d message(s) moved)"
            .formatted(moved, toMove);
    }
}
