package com.musicmind.llm;

/** Unchecked failure talking to the LLM provider (network, auth, or bad response). */
public class LLMException extends RuntimeException {
    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}
