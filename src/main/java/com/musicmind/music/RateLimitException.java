package com.musicmind.music;

/**
 * Raised when Spotify returns HTTP 429 (Too Many Requests). Carries the
 * {@code Retry-After} hint so {@link RetryingMusicService} (the Decorator) can
 * back off for the right amount of time before retrying.
 */
public class RateLimitException extends MusicServiceException {

    public RateLimitException(String message, int retryAfterSeconds) {
        super(message, 429, null, retryAfterSeconds);
    }
}
