package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.music.MusicService;
import com.musicmind.music.TrackList;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Command-pattern invoker. Runs {@link PlaylistCommand}s against Spotify, keeps the applied
 * ones on a bounded undo stack (newest first) and saves a snapshot before each modify so
 * undo also works in a later run, when the in-memory stack is empty (R10).
 *
 * <p>Callers editing an existing playlist use {@link #modify}, which snapshots first; a
 * snapshot that cannot be saved stops the change, because undo would not be possible.
 */
public class PlaylistManager {

    /** Applied commands, most recent first. */
    final Deque<PlaylistCommand> history = new ArrayDeque<>();

    private final Config config;
    private final MusicService music;
    private final MemoryManager memory;

    public PlaylistManager(Config config, MusicService music, MemoryManager memory) {
        this.config = config;
        this.music = music;
        this.memory = memory;
    }

    public void execute(PlaylistCommand command) {
        command.execute();
        push(command);
    }

    /**
     * Applies the commands in order as one undoable step. If one fails, the ones already
     * applied are undone and the failure is rethrown.
     */
    public void executeAll(List<PlaylistCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        if (commands.size() == 1) {
            execute(commands.get(0));
            return;
        }
        BatchCommand batch = new BatchCommand(config, commands);
        batch.execute();
        push(batch);
    }

    /**
     * Snapshots the playlist as it is now, then applies the commands as one undoable step.
     *
     * @return the id of the snapshot taken first
     */
    public int modify(Playlist current, List<PlaylistCommand> commands) {
        requireComplete(current.spotifyId());
        int snapshotId = snapshotBeforeModify(current);
        executeAll(commands);
        return snapshotId;
    }

    /**
     * Saves the playlist's current ids and name so a later undo or restore can return to it.
     *
     * @return the snapshot id
     * @throws PlaylistChangeException if the snapshot cannot be saved (database unavailable)
     */
    public int snapshotBeforeModify(Playlist current) {
        try {
            return memory.saveSnapshot(current);
        } catch (PersistenceException e) {
            throw new PlaylistChangeException(config.wording("snapshotSaveFailed"), e);
        }
    }

    /**
     * Reverts the most recent change to the playlist. With an empty stack (a new run) it
     * restores the playlist's latest snapshot instead.
     *
     * @return true if a command of this run was undone, false if the latest snapshot was restored
     */
    public boolean undo(String playlistId) {
        PlaylistCommand latest = null;
        for (PlaylistCommand c : history) {
            if (playlistId.equals(c.playlistId())) {
                latest = c;
                break;
            }
        }
        if (latest != null) {
            requireComplete(playlistId);
            latest.undo();
            history.remove(latest);
            return true;
        }
        Playlist snapshot;
        try {
            snapshot = memory.loadLatestSnapshot(playlistId);
        } catch (PersistenceException e) {
            throw new PlaylistChangeException(config.wording("snapshotUnavailable"), e);
        }
        if (snapshot == null) {
            throw new PlaylistChangeException(config.wording("undoNothing"));
        }
        restore(snapshot);
        return false;
    }

    /** Puts a playlist back to a saved snapshot (F12). */
    public void restore(int snapshotId) {
        Playlist snapshot;
        try {
            snapshot = memory.loadSnapshot(snapshotId);
        } catch (PersistenceException e) {
            throw new PlaylistChangeException(config.wording("snapshotUnavailable"), e);
        }
        if (snapshot == null) {
            throw new PlaylistChangeException(String.format(config.wording("restoreUnknown"), snapshotId));
        }
        restore(snapshot);
    }

    /** Runs a {@link RestoreSnapshotCommand} for the snapshot, so a restore sits on the undo stack like any change. */
    private void restore(Playlist snapshot) {
        requireComplete(snapshot.spotifyId());
        execute(new RestoreSnapshotCommand(config, music, snapshot.spotifyId(), snapshot.name(), snapshot.tracks()));
    }

    /** Refuses to work on a playlist longer than one Spotify read returns: a partial list would corrupt the change. */
    private void requireComplete(String playlistId) {
        if (TrackList.isTruncated(music.getPlaylistItems(playlistId))) {
            throw new PlaylistChangeException(String.format(config.wording("playlistTooLong"), config.spotifyMaxItems()));
        }
    }

    private void push(PlaylistCommand command) {
        history.addFirst(command);
        while (history.size() > config.commandHistoryLimit()) {
            history.removeLast();
        }
    }
}
