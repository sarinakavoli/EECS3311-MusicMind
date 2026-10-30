package com.musicmind.memory;

/** Unchecked wrapper for SQL/JDBC failures so the agent never handles {@code SQLException} directly. */
public class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
