package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.musicmind.agent.command.FakeMusicService.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistCommandsTest {

    private final Config config = Config.of(Map.of());
    private final FakeMusicService music = new FakeMusicService();

    @Test
    void createExecutesThenUndoUnfollows() {
        CreatePlaylistCommand c = new CreatePlaylistCommand(config, music, "user1", "Mix", List.of("spotify:track:a", "spotify:track:b"));
        assertNull(c.playlistId(), "no id before it runs");
        c.execute();
        assertEquals("pl1", c.playlistId());
        assertEquals(List.of("a", "b"), music.ids("pl1"));
        assertTrue(c.describe().contains("Mix"));
        c.undo();
        assertFalse(music.playlists.containsKey("pl1"));
        assertEquals("unfollow pl1", music.calls.get(music.calls.size() - 1));
    }

    @Test
    void createWithNoTracksAddsNothing() {
        new CreatePlaylistCommand(config, music, "user1", "Empty", List.of()).execute();
        assertEquals(List.of("create user1 Empty"), music.calls);
    }

    @Test
    void undoBeforeExecuteIsRejected() {
        CreatePlaylistCommand c = new CreatePlaylistCommand(config, music, "user1", "Mix", List.of());
        assertThrows(PlaylistChangeException.class, c::undo);
    }

    @Test
    void addAppendsAndUndoRemovesOnlyThoseTracks() {
        music.seed("p", "a", "b");
        AddItemsCommand c = new AddItemsCommand(config, music, "p", List.of(track("x"), track("y")));
        c.execute();
        assertEquals(List.of("a", "b", "x", "y"), music.ids("p"));
        c.undo();
        assertEquals(List.of("a", "b"), music.ids("p"));
        assertEquals("p", c.playlistId());
    }

    @Test
    void removeThenUndoRestoresOriginalPosition() {
        music.seed("p", "a", "b", "c", "d");
        RemoveTrackCommand c = new RemoveTrackCommand(config, music, "p", track("b"));
        c.execute();
        assertEquals(List.of("a", "c", "d"), music.ids("p"));
        c.undo();
        assertEquals(List.of("a", "b", "c", "d"), music.ids("p"));
    }

    @Test
    void removeLastTrackUndoNeedsNoReorder() {
        music.seed("p", "a", "b");
        RemoveTrackCommand c = new RemoveTrackCommand(config, music, "p", track("b"));
        c.execute();
        c.undo();
        assertEquals(List.of("a", "b"), music.ids("p"));
        assertTrue(music.calls.stream().noneMatch(s -> s.startsWith("reorder")));
    }

    @Test
    void removeOfMissingTrackFailsClearly() {
        music.seed("p", "a");
        RemoveTrackCommand c = new RemoveTrackCommand(config, music, "p", track("zzz"));
        PlaylistChangeException e = assertThrows(PlaylistChangeException.class, c::execute);
        assertTrue(e.getMessage().contains("zzz"));
        assertEquals(List.of("a"), music.ids("p"));
    }

    @Test
    void replaceKeepsPositionAndUndoRestoresOriginal() {
        music.seed("p", "a", "b", "c");
        ReplaceTrackCommand c = new ReplaceTrackCommand(config, music, "p", track("b"), track("n"));
        c.execute();
        assertEquals(List.of("a", "n", "c"), music.ids("p"));
        c.undo();
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void replaceThatFailsWhileAddingPutsTheOldTrackBack() {
        music.seed("p", "a", "b", "c");
        music.failOn = "add p [spotify:track:n]";
        ReplaceTrackCommand c = new ReplaceTrackCommand(config, music, "p", track("b"), track("n"));
        assertThrows(RuntimeException.class, c::execute);
        music.failOn = null;
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void reorderMovesForwardAndBackwardAndUndoes() {
        music.seed("p", "a", "b", "c", "d");
        ReorderCommand down = new ReorderCommand(config, music, "p", track("a"), 2);
        down.execute();
        assertEquals(List.of("b", "c", "a", "d"), music.ids("p"));
        down.undo();
        assertEquals(List.of("a", "b", "c", "d"), music.ids("p"));

        ReorderCommand up = new ReorderCommand(config, music, "p", track("d"), 0);
        up.execute();
        assertEquals(List.of("d", "a", "b", "c"), music.ids("p"));
        up.undo();
        assertEquals(List.of("a", "b", "c", "d"), music.ids("p"));
    }

    @Test
    void reorderPastTheEndIsClampedAndSamePositionIsANoOp() {
        music.seed("p", "a", "b", "c");
        ReorderCommand toEnd = new ReorderCommand(config, music, "p", track("a"), 99);
        toEnd.execute();
        assertEquals(List.of("b", "c", "a"), music.ids("p"));
        toEnd.undo();
        assertEquals(List.of("a", "b", "c"), music.ids("p"));

        music.calls.clear();
        ReorderCommand same = new ReorderCommand(config, music, "p", track("b"), 1);
        same.execute();
        same.undo();
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void describeNamesTheTrackByTitleOrId() {
        Track untitled = new Track("zz", "spotify:track:zz", "", 0, false, List.of());
        assertTrue(new RemoveTrackCommand(config, music, "p", track("a")).describe().contains("Title a"));
        assertTrue(new RemoveTrackCommand(config, music, "p", untitled).describe().contains("zz"));
    }

    @Test
    void removeUndoRestoresEveryDuplicateCopyInPlace() {
        music.seed("p", "a", "b", "c", "b", "d", "b");
        RemoveTrackCommand c = new RemoveTrackCommand(config, music, "p", track("b"));
        c.execute();
        assertEquals(List.of("a", "c", "d"), music.ids("p"));
        c.undo();
        assertEquals(List.of("a", "b", "c", "b", "d", "b"), music.ids("p"));
    }

    @Test
    void replaceWithDuplicatesUndoesToTheOriginalOrder() {
        music.seed("p", "a", "b", "c", "b", "d");
        ReplaceTrackCommand c = new ReplaceTrackCommand(config, music, "p", track("b"), track("n"));
        c.execute();
        assertEquals(List.of("a", "n", "c", "d"), music.ids("p"));
        c.undo();
        assertEquals(List.of("a", "b", "c", "b", "d"), music.ids("p"));
    }
}
