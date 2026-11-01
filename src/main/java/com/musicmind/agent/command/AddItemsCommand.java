package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/** Appends tracks to a playlist; undo removes exactly those tracks. */
public class AddItemsCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final List<Track> tracks;

    public AddItemsCommand(Config config, MusicService music, String playlistId, List<Track> tracks) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("AddItemsCommand needs at least one track.");
        }
        this.tracks = List.copyOf(tracks);
    }

    @Override
    public void execute() {
        music.addItems(playlistId, uris());
    }

    @Override
    public void undo() {
        music.removeItems(playlistId, uris());
    }

    private List<String> uris() {
        return tracks.stream().map(PlaylistOps::uri).toList();
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdAdd"), tracks.size(),
                String.join(", ", tracks.stream().map(PlaylistOps::label).toList()));
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
