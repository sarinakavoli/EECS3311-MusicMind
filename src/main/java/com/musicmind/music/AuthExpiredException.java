package com.musicmind.music;

/**
 * Raised when Spotify rejects our credentials (HTTP 401) even after a forced
 * token refresh, or when no refresh is possible. The user must log in again;
 * it is never retried automatically.
 */
public class AuthExpiredException extends MusicServiceException {

    public AuthExpiredException(String message) {
        super(message, 401);
    }

    public AuthExpiredException(String message, Throwable cause) {
        super(message, 401, cause);
    }
}
