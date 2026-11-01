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

    @Test
    void restoreSnapshotSetsTheSnapshotsTracksAndUndoPutsTheOldOnesBack() {
        music.seed("p", "x", "y", "x");
        RestoreSnapshotCommand c = new RestoreSnapshotCommand(config, music, "p", "Mix", List.of(track("a"), track("b")));
        assertThrows(PlaylistChangeException.class, c::undo);   // not run yet
        c.execute();
        assertEquals(List.of("a", "b"), music.ids("p"));
        assertTrue(c.describe().contains("Mix"));
        c.undo();
        assertEquals(List.of("x", "y", "x"), music.ids("p"));
    }

    @Test
    void restoreSnapshotLeavesAnIdenticalPlaylistUntouched() {
        music.seed("p", "a", "b");
        new RestoreSnapshotCommand(config, music, "p", "Mix", List.of(track("a"), track("b"))).execute();
        assertTrue(music.calls.stream().noneMatch(call -> call.startsWith("remove") || call.startsWith("add")));
    }

    @Test
    void restoreSnapshotToAnEmptySnapshotEmptiesThePlaylist() {
        music.seed("p", "a");
        new RestoreSnapshotCommand(config, music, "p", "Mix", List.of()).execute();
        assertTrue(music.ids("p").isEmpty());
    }

    /** Spotify holds a local file first; reads leave it out and say where the others really are. */
    private static FakeMusicService withHiddenLocalFile() {
        FakeMusicService music = new FakeMusicService() {
            @Override public List<Track> getPlaylistItems(String playlistId) {
                List<Track> all = super.getPlaylistItems(playlistId);
                List<Track> shown = new java.util.ArrayList<>();
                List<Integer> positions = new java.util.ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    if (!all.get(i).id().equals("local")) {
                        shown.add(all.get(i));
                        positions.add(i);
                    }
                }
                return new com.musicmind.music.TrackList(shown, false, positions);
            }
        };
        music.seed("p", "local", "a", "b", "c");
        return music;
    }

    @Test
    void aMoveUsesTheRealPositionWhenAnEntryIsHiddenFromTheRead() {
        FakeMusicService music = withHiddenLocalFile();
        ReorderCommand command = new ReorderCommand(config, music, "p", track("c"), 0);

        command.execute();
        assertEquals(List.of("local", "c", "a", "b"), music.ids("p"));
        command.undo();
        assertEquals(List.of("local", "a", "b", "c"), music.ids("p"));
    }

    @Test
    void undoOfARemovePutsTheTrackBackAroundAHiddenEntry() {
        FakeMusicService music = withHiddenLocalFile();
        RemoveTrackCommand command = new RemoveTrackCommand(config, music, "p", track("a"));

        command.execute();
        command.undo();
        assertEquals(List.of("local", "a", "b", "c"), music.ids("p"));
    }

    /** Counts playlist reads, which are the costly paged calls. */
    private static class CountingReads extends FakeMusicService {
        int reads;
        @Override public List<Track> getPlaylistItems(String playlistId) {
            reads++;
            return super.getPlaylistItems(playlistId);
        }
    }

    @Test
    void removingAndMovingEachReadThePlaylistOnce() {
        CountingReads music = new CountingReads();
        music.seed("p", "a", "b", "c");

        new RemoveTrackCommand(config, music, "p", track("a")).execute();
        assertEquals(1, music.reads);

        music.reads = 0;
        new ReorderCommand(config, music, "p", track("c"), 0).execute();
        assertEquals(1, music.reads);
    }

    @Test
    void aFailedMoveDuringUndoOfARemoveLeavesNoExtraCopySoTheUndoCanBeRepeated() {
        FakeMusicService music = new FakeMusicService();
        music.seed("p", "a", "b", "c");
        RemoveTrackCommand command = new RemoveTrackCommand(config, music, "p", track("a"));
        command.execute();

        music.failOn = "reorder";
        music.failOnce = true;
        assertThrows(RuntimeException.class, command::undo);
        assertEquals(List.of("b", "c"), music.ids("p"));

        command.undo();
        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void aFailedFinalMoveOfAReplaceLeavesThePlaylistAsItWas() {
        FakeMusicService music = new FakeMusicService();
        music.seed("p", "a", "b", "c");
        ReplaceTrackCommand command = new ReplaceTrackCommand(config, music, "p", track("a"), track("x"));

        music.failOn = "reorder";
        music.failOnce = true;
        assertThrows(RuntimeException.class, command::execute);

        assertEquals(List.of("a", "b", "c"), music.ids("p"));
    }

    @Test
    void aFailedUndoOfARestorePutsTheRestoredTracksBack() {
        FakeMusicService music = new FakeMusicService();
        music.seed("p", "a", "b");
        RestoreSnapshotCommand command = new RestoreSnapshotCommand(config, music, "p", "Mix", List.of(track("c")));
        command.execute();

        music.failOn = "add";
        music.failOnce = true;
        assertThrows(RuntimeException.class, command::undo);

        assertEquals(List.of("c"), music.ids("p"));
    }
}
