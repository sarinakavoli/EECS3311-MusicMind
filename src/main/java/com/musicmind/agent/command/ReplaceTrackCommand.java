package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/** Swaps one track for another at the same position; undo swaps them back. */
public class ReplaceTrackCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final Track oldTrack;
    private final Track newTrack;
    private int index = -1;
    private int size;

    public ReplaceTrackCommand(Config config, MusicService music, String playlistId, Track oldTrack, Track newTrack) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.oldTrack = oldTrack;
        this.newTrack = newTrack;
    }

    @Override
    public void execute() {
        index = PlaylistOps.indexOf(config, music, playlistId, oldTrack);
        size = music.getPlaylistItems(playlistId).size();
        swap(oldTrack, newTrack);
    }

    @Override
    public void undo() {
        if (index < 0) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        swap(newTrack, oldTrack);
    }

    /** Removes {@code out}, appends {@code in}, moves it to the index; if the add fails the removed track is put back. */
    private void swap(Track out, Track in) {
        music.removeItems(playlistId, List.of(PlaylistOps.uri(out)));
        try {
            music.addItems(playlistId, List.of(PlaylistOps.uri(in)));
        } catch (RuntimeException e) {
            putBack(out, e);
            throw e;
        }
        PlaylistOps.move(music, playlistId, size - 1, index);
    }

    private void putBack(Track out, RuntimeException cause) {
        try {
            music.addItems(playlistId, List.of(PlaylistOps.uri(out)));
            PlaylistOps.move(music, playlistId, size - 1, index);
        } catch (RuntimeException secondary) {
            cause.addSuppressed(secondary);
        }
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdReplace"), PlaylistOps.label(oldTrack), PlaylistOps.label(newTrack));
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
