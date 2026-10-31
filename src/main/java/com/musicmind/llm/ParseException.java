package com.musicmind.llm;

/** The LLM's reply was malformed or failed verification, so callers should use their documented fallback. */
public class ParseException extends LLMException {
    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
