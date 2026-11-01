package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/**
 * Sets a playlist to exactly a snapshot's tracks in its order (F12 restore, replayed through the Command
 * mechanism); undo sets it back to the tracks it held before. If setting it fails part way, the previous
 * tracks are put back and the failure is reported.
 */
public class RestoreSnapshotCommand implements PlaylistCommand {

    private final Config config;
    private final MusicService music;
    private final String playlistId;
    private final String name;
    private final List<String> wanted;
    private List<String> before;

    public RestoreSnapshotCommand(Config config, MusicService music, String playlistId, String name, List<Track> snapshotTracks) {
        this.config = config;
        this.music = music;
        this.playlistId = playlistId;
        this.name = name;
        this.wanted = snapshotTracks.stream().map(PlaylistOps::uri).toList();
    }

    @Override
    public void execute() {
        List<String> now = currentUris();
        before = now;
        try {
            set(now, wanted);
        } catch (RuntimeException e) {
            PlaylistChangeException failure = new PlaylistChangeException(config.wording("restoreFailed"), e);
            putBack(failure);
            throw failure;
        }
    }

    @Override
    public void undo() {
        if (before == null) {
            throw new PlaylistChangeException(config.wording("cmdNotRun"));
        }
        set(currentUris(), before);
    }

    private void set(List<String> now, List<String> target) {
        if (now.equals(target)) {
            return;
        }
        if (!now.isEmpty()) {
            music.removeItems(playlistId, now);
        }
        if (!target.isEmpty()) {
            music.addItems(playlistId, target);
        }
    }

    /** Returns the playlist to its tracks from before the failed restore; a failure here is kept as suppressed. */
    private void putBack(PlaylistChangeException failure) {
        try {
            set(currentUris(), before);
        } catch (RuntimeException secondary) {
            failure.addSuppressed(secondary);
        }
    }

    private List<String> currentUris() {
        return music.getPlaylistItems(playlistId).stream().map(PlaylistOps::uri).toList();
    }

    @Override
    public String describe() {
        return String.format(config.wording("cmdRestore"), name, wanted.size());
    }

    @Override
    public String playlistId() {
        return playlistId;
    }
}
