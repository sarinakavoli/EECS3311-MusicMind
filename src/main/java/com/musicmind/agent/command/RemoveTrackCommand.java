package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

/** Removes a track; undo re-adds it and moves it back to the index it had (R12). */
public class RemoveTrackCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final Track track;
    private int originalIndex = -1;
    private int sizeBefore;

    public RemoveTrackCommand(Config config, MusicService music, String playlistId, Track track) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.track = track;
    }

    @Override
    public void execute() {
        originalIndex = PlaylistOps.indexOf(config, music, playlistId, track);
        sizeBefore = music.getPlaylistItems(playlistId).size();
        music.removeItems(playlistId, java.util.List.of(PlaylistOps.uri(track)));
    }

    @Override
    public void undo() {
        if (originalIndex < 0) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        music.addItems(playlistId, java.util.List.of(PlaylistOps.uri(track)));
        PlaylistOps.move(music, playlistId, sizeBefore - 1, originalIndex);
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdRemove"), PlaylistOps.label(track));
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
