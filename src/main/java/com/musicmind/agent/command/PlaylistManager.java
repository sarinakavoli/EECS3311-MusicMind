package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PersistenceException;
import com.musicmind.music.MusicService;

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

    /** Snapshots the playlist as it is now, then applies the commands as one undoable step. */
    public void modify(Playlist current, List<PlaylistCommand> commands) {
        snapshotBeforeModify(current);
        executeAll(commands);
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
     */
    public void undo(String playlistId) {
        PlaylistCommand latest = null;
        for (PlaylistCommand c : history) {
            if (playlistId.equals(c.playlistId())) {
                latest = c;
                break;
            }
        }
        if (latest != null) {
            latest.undo();
            history.remove(latest);
            return;
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

    /**
     * Makes the playlist hold exactly the snapshot's tracks in its order; the name is not changed.
     * If that fails part way, the playlist's previous tracks are put back and the failure is reported.
     */
    private void restore(Playlist snapshot) {
        String id = snapshot.spotifyId();
        List<String> wanted = snapshot.tracks().stream().map(PlaylistOps::uri).toList();
        List<String> now = music.getPlaylistItems(id).stream().map(PlaylistOps::uri).toList();
        if (now.equals(wanted)) {
            return;
        }
        try {
            if (!now.isEmpty()) {
                music.removeItems(id, now);
            }
            if (!wanted.isEmpty()) {
                music.addItems(id, wanted);
            }
            // Earlier commands described the old content; undo must now fall back to the snapshot taken before this.
            history.removeIf(c -> id.equals(c.playlistId()));
        } catch (RuntimeException e) {
            PlaylistChangeException failure = new PlaylistChangeException(config.wording("restoreFailed"), e);
            putBack(id, now, failure);
            throw failure;
        }
    }

    /** Returns the playlist to the tracks it had before a failed restore; a failure here is kept as suppressed. */
    private void putBack(String id, List<String> before, PlaylistChangeException failure) {
        try {
            List<String> left = music.getPlaylistItems(id).stream().map(PlaylistOps::uri).toList();
            if (!left.isEmpty()) {
                music.removeItems(id, left);
            }
            if (!before.isEmpty()) {
                music.addItems(id, before);
            }
        } catch (RuntimeException secondary) {
            failure.addSuppressed(secondary);
        }
    }

    private void push(PlaylistCommand command) {
        history.addFirst(command);
        while (history.size() > config.commandHistoryLimit()) {
            history.removeLast();
        }
    }
}
