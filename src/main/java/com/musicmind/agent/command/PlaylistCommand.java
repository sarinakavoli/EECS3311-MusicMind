package com.musicmind.agent.command;

/**
 * Command pattern: a reversible playlist operation. PlaylistManager (the
 * invoker) executes commands and keeps them on an undo stack; MusicService is
 * the receiver that performs the actual change on Spotify.
 */
public interface PlaylistCommand {
    void execute();
    void undo();

    /** Human-readable summary used in change reports and history. */
    String describe();

    /** The playlist this command changes, or {@code null} for a create that has not run yet. */
    String playlistId();

    /**
     * What the playlist's tracks would be after this command, without touching Spotify; used to check an
     * edit before any of it is applied. A command that only reorders leaves the list as it is.
     */
    default java.util.List<com.musicmind.domain.Track> simulate(java.util.List<com.musicmind.domain.Track> tracks) {
        return tracks;
    }
}
