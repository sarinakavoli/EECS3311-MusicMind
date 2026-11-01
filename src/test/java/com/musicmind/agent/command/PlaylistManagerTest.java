package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.MusicServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.musicmind.agent.command.FakeMusicService.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistManagerTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());
    private final FakeMusicService music = new FakeMusicService();

    private MemoryManager memory(Database database) {
        return new MemoryManager(database,
                new PreferenceRepository(database, currentUser::get),
                new FeedbackRepository(database, currentUser::get),
                new SessionRepository(database, currentUser::get),
                new PlaylistHistoryRepository(database, currentUser::get));
    }

    private PlaylistManager manager() {
        return new PlaylistManager(config, music, memory(db));
    }

    private Playlist current(String id) {
        return new Playlist(id, "Mix", music.getPlaylistItems(id));
    }

    @Test
    void executePushesAndUndoReversesMostRecentFirst() {
        music.seed("p", "a", "b", "c");
        PlaylistManager m = manager();
        m.execute(new RemoveTrackCommand(config, music, "p", track("a")));
        m.execute(new RemoveTrackCommand(config, music, "p", track("c")));
        assertEquals(2, m.history.size());
        assertEquals(List.of("b"), music.ids("p"));
        m.undo("p");
        assertEquals(List.of("b", "c"), music.ids("p"));
        m.undo("p");
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
        assertTrue(m.history.isEmpty());
    }

    @Test
    void undoOnlyTouchesCommandsOfThatPlaylist() {
        music.seed("p", "a", "b");
        music.seed("q", "x", "y");
        PlaylistManager m = manager();
        m.execute(new RemoveTrackCommand(config, music, "p", track("a")));
        m.execute(new RemoveTrackCommand(config, music, "q", track("x")));
        m.undo("p");
        assertEquals(List.of("a", "b"), music.ids("p"));
        assertEquals(List.of("y"), music.ids("q"));
        assertEquals(1, m.history.size());
    }

    @Test
    void failedExecuteIsNotRecorded() {
        music.seed("p", "a");
        PlaylistManager m = manager();
        assertThrows(PlaylistChangeException.class,
                () -> m.execute(new RemoveTrackCommand(config, music, "p", track("nope"))));
        assertTrue(m.history.isEmpty());
    }

    @Test
    void executeAllRollsBackAppliedCommandsThenRethrows() {
        music.seed("p", "a", "b", "c");
        PlaylistManager m = manager();
        music.failOn = "add p [spotify:track:x]";
        MusicServiceException e = assertThrows(MusicServiceException.class, () -> m.executeAll(List.of(
                new RemoveTrackCommand(config, music, "p", track("a")),
                new ReorderCommand(config, music, "p", track("c"), 0),
                new AddItemsCommand(config, music, "p", List.of(track("x"))))));
        assertTrue(e.getMessage().contains("boom"));
        music.failOn = null;
        assertEquals(List.of("a", "b", "c"), music.ids("p"), "the two applied commands were undone");
        assertTrue(m.history.isEmpty());
    }

    @Test
    void aSuccessfulBatchUndoesAsOneStep() {
        music.seed("p", "a", "b", "c");
        PlaylistManager m = manager();
        m.executeAll(List.of(
                new RemoveTrackCommand(config, music, "p", track("a")),
                new AddItemsCommand(config, music, "p", List.of(track("x")))));
        assertEquals(List.of("b", "c", "x"), music.ids("p"));
        assertEquals(1, m.history.size());
        assertTrue(m.history.peekFirst().describe().contains("Title a"));
        m.undo("p");
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void executeAllWithNothingDoesNothing() {
        PlaylistManager m = manager();
        m.executeAll(List.of());
        assertTrue(m.history.isEmpty());
    }

    @Test
    void historyIsBounded() {
        music.seed("p", "a", "b", "c", "d");
        PlaylistManager m = new PlaylistManager(config.withTunables(Map.of("command.historyLimit", "2")),
                music, memory(db));
        for (String id : List.of("a", "b", "c")) {
            m.execute(new RemoveTrackCommand(config, music, "p", track(id)));
        }
        assertEquals(2, m.history.size());
    }

    @Test
    void undoWithEmptyStackRestoresTheLatestSnapshot() {
        music.seed("p", "a", "b", "c");
        PlaylistManager first = manager();
        first.modify(current("p"), List.of(new RemoveTrackCommand(config, music, "p", track("b")),
                new AddItemsCommand(config, music, "p", List.of(track("x")))));
        assertEquals(List.of("a", "c", "x"), music.ids("p"));

        PlaylistManager laterProcess = manager();   // a new CLI run: empty in-memory stack
        assertTrue(laterProcess.history.isEmpty());
        laterProcess.undo("p");
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void restoreUsesTheSnapshotsOrderAndOnlyItsTracks() {
        music.seed("p", "a", "b", "c");
        PlaylistManager m = manager();
        int id = m.snapshotBeforeModify(current("p"));
        music.seed("p", "z", "c");
        m.restore(id);
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void restoreOfUnknownSnapshotSaysSo() {
        PlaylistManager m = manager();
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> m.restore(9999));
        assertTrue(e.getMessage().contains("9999"));
    }

    @Test
    void undoWithNothingSavedGivesAClearMessage() {
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> manager().undo("never-edited"));
        assertEquals(config.wording("undoNothing"), e.getMessage());
    }

    @Test
    void undoFromSnapshotWithDatabaseDownGivesAClearMessage() {
        Database dead = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        PlaylistManager m = new PlaylistManager(config, music, memory(dead));
        music.seed("p", "a");
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () -> m.undo("p"));
        assertEquals(config.wording("snapshotUnavailable"), e.getMessage());
        assertThrows(PlaylistChangeException.class, () -> m.restore(1));
    }

    @Test
    void modifyWithDatabaseDownChangesNothingAndSaysWhy() {
        Database dead = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        PlaylistManager m = new PlaylistManager(config, music, memory(dead));
        music.seed("p", "a", "b");
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, () ->
                m.modify(current("p"), List.of(new RemoveTrackCommand(config, music, "p", track("a")))));
        assertEquals(config.wording("snapshotSaveFailed"), e.getMessage());
        assertEquals(List.of("a", "b"), music.ids("p"));
        assertTrue(m.history.isEmpty());
    }
}
