package com.musicmind.agent;

/** The goal could not be understood well enough to plan; the message tells the user what to say instead. */
public class ClarificationNeededException extends RuntimeException {
    public ClarificationNeededException(String message) {
        super(message);
    }
}
