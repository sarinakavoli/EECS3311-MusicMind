package com.musicmind.memory.repository;

import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;
import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistHistoryRepositoryTest extends PostgresTestBase {

    private PlaylistHistoryRepository repo() {
        return new PlaylistHistoryRepository(db, currentUser::get);
    }

    private static Track track(String id) {
        return new Track(id, "spotify:track:" + id, "Secret Title", 1000, false, List.of("a"));
    }

    private static Playlist playlist(String name, String... ids) {
        Playlist p = new Playlist("pl-1", name, List.of());
        for (String id : ids) p.addTrack(track(id));
        return p;
    }

    @Test
    void snapshotStoresIdsAndOrderOnlyNeverTitles() {
        PlaylistHistoryRepository repo = repo();
        int id = repo.saveSnapshot(playlist("Run", "c", "a", "b"));

        Playlist loaded = repo.findById(String.valueOf(id));
        assertEquals("pl-1", loaded.spotifyId());
        assertEquals("Run", loaded.name());
        assertEquals(List.of("c", "a", "b"), loaded.tracks().stream().map(Track::id).toList());
        assertEquals(List.of("spotify:track:c", "spotify:track:a", "spotify:track:b"),
                loaded.tracks().stream().map(Track::uri).toList());
        assertTrue(loaded.tracks().stream().allMatch(t -> t.title().isBlank()));
    }

    @Test
    void snapshotsAreNewestFirstAndPerUser() {
        PlaylistHistoryRepository repo = repo();
        int first = repo.saveSnapshot(playlist("v1", "a"));
        repo.save(playlist("v2", "a", "b"));
        currentUser.set("user-2");
        repo.saveSnapshot(playlist("other", "z"));

        List<Playlist> mine = repo.findByUser("user-1");
        assertEquals(List.of("v2", "v1"), mine.stream().map(Playlist::name).toList());
        assertEquals(1, repo.findByUser("user-2").size());
        assertTrue(first > 0);
    }

    @Test
    void deleteAndUnknownOrMalformedIds() {
        PlaylistHistoryRepository repo = repo();
        int id = repo.saveSnapshot(playlist("Run", "a"));
        assertNull(repo.findById("99999"));
        assertNull(repo.findById("not-a-number"));

        currentUser.set("user-2");
        assertNull(repo.findById(String.valueOf(id)), "snapshots belong to their owner");
        currentUser.set("user-1");
        repo.delete(String.valueOf(id));
        assertNull(repo.findById(String.valueOf(id)));
    }

    @Test
    void playlistWithoutIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> repo().saveSnapshot(new Playlist("unsaved")));
    }
}
