package com.musicmind.music;

/**
 * Raised when Spotify returns HTTP 429 (Too Many Requests). Carries the
 * {@code Retry-After} hint so {@link RetryingMusicService} (the Decorator) can
 * back off for the right amount of time before retrying.
 */
public class RateLimitException extends MusicServiceException {

    private final int retryAfterSeconds;

    public RateLimitException(String message, int retryAfterSeconds) {
        super(message, 429);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /** Seconds to wait before retrying, as advised by the {@code Retry-After} header. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
