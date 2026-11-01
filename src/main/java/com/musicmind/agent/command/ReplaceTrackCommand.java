package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/**
 * Swaps a track (all its copies) for one new track at the first copy's position; undo removes the
 * new track and puts every old copy back at its original index.
 */
public class ReplaceTrackCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final Track oldTrack;
    private final Track newTrack;
    private List<Integer> oldIndexes = List.of();
    private int sizeWithout;

    public ReplaceTrackCommand(Config config, MusicService music, String playlistId, Track oldTrack, Track newTrack) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.oldTrack = oldTrack;
        this.newTrack = newTrack;
    }

    @Override
    public void execute() {
        List<Track> items = music.getPlaylistItems(playlistId);
        oldIndexes = PlaylistOps.indexesOf(config, items, oldTrack);
        sizeWithout = items.size() - oldIndexes.size();
        music.removeItems(playlistId, List.of(PlaylistOps.uri(oldTrack)));
        try {
            music.addItems(playlistId, List.of(PlaylistOps.uri(newTrack)));
        } catch (RuntimeException e) {
            try {
                PlaylistOps.restoreCopies(music, playlistId, oldTrack, oldIndexes, sizeWithout);
            } catch (RuntimeException secondary) {
                e.addSuppressed(secondary);
            }
            throw e;
        }
        PlaylistOps.move(music, playlistId, sizeWithout, oldIndexes.get(0));
    }

    @Override
    public void undo() {
        if (oldIndexes.isEmpty()) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        music.removeItems(playlistId, List.of(PlaylistOps.uri(newTrack)));
        try {
            PlaylistOps.restoreCopies(music, playlistId, oldTrack, oldIndexes, sizeWithout);
        } catch (RuntimeException e) {
            try {
                music.addItems(playlistId, List.of(PlaylistOps.uri(newTrack)));
                PlaylistOps.move(music, playlistId, sizeWithout, oldIndexes.get(0));
            } catch (RuntimeException secondary) {
                e.addSuppressed(secondary);
            }
            throw e;
        }
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdReplace"), PlaylistOps.label(oldTrack), PlaylistOps.label(newTrack));
    }

    @Override
    public List<Track> simulate(List<Track> tracks) {
        List<Track> out = new java.util.ArrayList<>();
        boolean placed = false;
        for (Track t : tracks) {
            if (!t.id().equals(oldTrack.id())) {
                out.add(t);
            } else if (!placed) {
                out.add(newTrack);
                placed = true;
            }
        }
        return out;
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
