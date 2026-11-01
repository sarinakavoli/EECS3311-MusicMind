package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;
import java.util.function.Supplier;

/** Appends tracks to a playlist; undo removes exactly those tracks. */
public class AddItemsCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final Supplier<String> playlistId;
    private final List<Track> tracks;

    public AddItemsCommand(Config config, MusicService music, String playlistId, List<Track> tracks) {
        this(config, music, () -> playlistId, tracks);
    }

    /**
     * For a playlist that does not exist yet: the id is asked for when the command runs, so it can follow a
     * {@link CreatePlaylistCommand} in the same batch ({@code create::playlistId}).
     */
    public AddItemsCommand(Config config, MusicService music, Supplier<String> playlistId, List<Track> tracks) {
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
        music.addItems(playlistId.get(), uris());
    }

    @Override
    public void undo() {
        music.removeItems(playlistId.get(), uris());
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
    public List<Track> simulate(List<Track> existing) {
        List<Track> out = new java.util.ArrayList<>(existing);
        out.addAll(tracks);
        return out;
    }

    @Override
    public String playlistId() {
        return playlistId.get();
    }
}
