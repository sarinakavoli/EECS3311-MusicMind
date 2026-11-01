package com.musicmind.agent.command;

/**
 * A restore found that the Spotify playlist of the snapshot no longer exists. The caller can offer to recreate
 * it from the same snapshot. The message is user-facing wording, safe to show as is.
 */
public class PlaylistMissingException extends PlaylistChangeException {

    private final int snapshotId;

    public PlaylistMissingException(int snapshotId, String message, Throwable cause) {
        super(message, cause);
        this.snapshotId = snapshotId;
    }

    /** The snapshot whose playlist is gone. */
    public int snapshotId() {
        return snapshotId;
    }
}
