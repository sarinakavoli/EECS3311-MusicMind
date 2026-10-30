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
}
