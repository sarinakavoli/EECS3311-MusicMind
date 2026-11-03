package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.music.MusicService;

/**
 * Removes a playlist from the user's library (Spotify's unfollow, the only "delete" it has). It cannot be
 * undone in place, because Spotify cannot bring a playlist back under the same id; the way back is F12,
 * which recreates the playlist from the snapshot {@link PlaylistManager#delete} saves first.
 */
public class DeletePlaylistCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final String name;
    private final int trackCount;

    public DeletePlaylistCommand(Config config, MusicService music, String playlistId, String name, int trackCount) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.name = name;
        this.trackCount = trackCount;
    }

    @Override
    public void execute() {
        music.unfollowPlaylist(playlistId);
    }

    @Override
    public void undo() {
        throw new PlaylistChangeException(config.wording("deleteUndoUnavailable"));
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdDelete"), name, trackCount);
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
