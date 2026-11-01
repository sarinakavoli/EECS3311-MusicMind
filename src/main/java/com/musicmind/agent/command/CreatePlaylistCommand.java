package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.music.MusicService;

import java.util.List;

/** Creates a playlist and fills it; undo removes it from the user's library (R11). */
public class CreatePlaylistCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String userId;
    private final String name;
    private final List<String> uris;
    private String playlistId;

    public CreatePlaylistCommand(Config config, MusicService music, String userId, String name, List<String> uris) {
        this.config = config;
        this.music = music;
        this.userId = userId;
        this.name = name;
        this.uris = List.copyOf(uris);
    }

    @Override
    public void execute() {
        String id = music.createPlaylist(userId, name).spotifyId();
        playlistId = id;
        if (!uris.isEmpty()) {
            music.addItems(id, uris);
        }
    }

    @Override
    public void undo() {
        if (playlistId == null) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        music.unfollowPlaylist(playlistId);
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdCreate"), name, uris.size());
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
