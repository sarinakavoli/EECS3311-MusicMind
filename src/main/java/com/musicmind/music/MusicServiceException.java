package com.musicmind.music;

/**
 * Thrown when a {@link MusicService} call fails (network error, non-success HTTP
 * status, or malformed response). Unchecked so the agent pipeline and UI can
 * handle it at a single boundary.
 */
public class MusicServiceException extends RuntimeException {

    private final int statusCode;
    private final int retryAfterSeconds;

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
        this(message, statusCode, cause, 0);
    }

    public MusicServiceException(String message, int statusCode, Throwable cause, int retryAfterSeconds) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /** HTTP status code that caused the failure, or -1 if not applicable. */
    public int statusCode() {
        return statusCode;
    }

    /** Seconds Spotify asked us to wait ({@code Retry-After}), or 0 if it gave no hint. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
