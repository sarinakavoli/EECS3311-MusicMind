package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/** Removes a track (every copy of it); undo re-adds each copy and moves it back to the index it had (R12). */
public class RemoveTrackCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final Track track;
    private List<Integer> originalIndexes = List.of();
    private int sizeAfter;

    public RemoveTrackCommand(Config config, MusicService music, String playlistId, Track track) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.track = track;
    }

    @Override
    public void execute() {
        originalIndexes = PlaylistOps.indexesOf(config, music, playlistId, track);
        sizeAfter = music.getPlaylistItems(playlistId).size() - originalIndexes.size();
        music.removeItems(playlistId, List.of(PlaylistOps.uri(track)));
    }

    @Override
    public void undo() {
        if (originalIndexes.isEmpty()) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        PlaylistOps.restoreCopies(music, playlistId, track, originalIndexes, sizeAfter);
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdRemove"), PlaylistOps.label(track));
    }

    @Override
    public List<Track> simulate(List<Track> tracks) {
        return tracks.stream().filter(t -> !t.id().equals(track.id())).toList();
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
