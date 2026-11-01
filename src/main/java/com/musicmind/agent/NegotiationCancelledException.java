package com.musicmind.agent;

/** The user declined every relaxation of an infeasible request (F11); nothing was changed. */
public class NegotiationCancelledException extends RuntimeException {
    public NegotiationCancelledException(String message) {
        super(message);
    }
}
