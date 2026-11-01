package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/** Moves a track to a 0-based position (clamped to the end of the list); undo moves it back. */
public class ReorderCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final Track track;
    private final int requestedPosition;
    private int from = -1;
    private int to;

    public ReorderCommand(Config config, MusicService music, String playlistId, Track track, int position) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.track = track;
        this.requestedPosition = Math.max(0, position);
    }

    @Override
    public void execute() {
        List<Track> items = music.getPlaylistItems(playlistId);
        from = PlaylistOps.indexOf(config, items, track);
        to = Math.min(requestedPosition, items.size() - 1);
        PlaylistOps.move(music, playlistId, items, from, to);
    }

    @Override
    public void undo() {
        if (from < 0) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        PlaylistOps.move(music, playlistId, to, from);
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdReorder"), PlaylistOps.label(track), requestedPosition + 1);
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
