package com.musicmind.agent.command;

/**
 * A playlist change, undo or restore that cannot be done. The message is user-facing wording
 * from {@code musicmind.properties}, safe to show as is.
 */
public class PlaylistChangeException extends RuntimeException {
    public PlaylistChangeException(String message) {
        super(message);
    }

    public PlaylistChangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
