package com.musicmind.music;

/**
 * Thrown when a {@link MusicService} call fails (network error, non-success HTTP
 * status, or malformed response). Unchecked so the agent pipeline and UI can
 * handle it at a single boundary.
 */
public class MusicServiceException extends RuntimeException {

    private final int statusCode;

    public MusicServiceException(String message) {
        this(message, -1, null);
    }

    public MusicServiceException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public MusicServiceException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public MusicServiceException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status code that caused the failure, or -1 if not applicable. */
    public int statusCode() {
        return statusCode;
    }
}
