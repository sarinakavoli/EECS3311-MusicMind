package com.musicmind.tool;

import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Track;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyToolTest {

    private static final Track T = new Track("t1", "spotify:track:t1", "x", 1000, false, List.of("a1"));
    private final List<String> calls = new ArrayList<>();

    private final StubMusicService music = new StubMusicService() {
        @Override public List<Track> getTopItems(String type, String range) {
            calls.add("top:" + type + ":" + range);
            return List.of(T);
        }
        @Override public List<Artist> getTopArtists(String range) {
            calls.add("artists:" + range);
            return List.of(new Artist("a1", "A", List.of("pop")));
        }
        @Override public List<Track> getSavedTracks() { calls.add("saved"); return List.of(T); }
        @Override public List<Track> getRecentlyPlayed() { calls.add("recent"); return List.of(T); }
        @Override public List<Track> search(String q, int limit) { calls.add("search:" + q + ":" + limit); return List.of(T); }
        @Override public List<Album> getArtistAlbums(String id) { calls.add("albums:" + id); return List.of(new Album("al", "n", "2020")); }
        @Override public List<Track> getAlbumTracks(String id) { calls.add("albumTracks:" + id); return List.of(T); }
        @Override public List<Track> getPlaylistItems(String id) { calls.add("items:" + id); return List.of(T); }
    };

    private ToolManager manager() {
        ToolManager tm = new ToolManager();
        SpotifyTool.allFor(music).forEach(tm::register);
        return tm;
    }

    @Test
    void allForCreatesOneToolPerOperationWithTheContractNames() {
        Set<String> names = SpotifyTool.allFor(music).stream().map(Tool::name).collect(Collectors.toSet());
        assertEquals(Set.of("getTopItems", "getSavedTracks", "getRecentlyPlayed", "search",
                "getArtistAlbums", "getAlbumTracks", "getPlaylistItems"), names);
    }

    @Test
    void getTopItemsDefaultsToTracksAndPassesRange() {
        ToolResult r = manager().execute("getTopItems", Map.of("range", "short_term"));
        assertTrue(r.ok());
        assertEquals(List.of(T), r.payload());
        assertEquals(List.of("top:tracks:short_term"), calls);
    }

    @Test
    void getTopItemsWithTypeArtistsRoutesToGetTopArtists() {
        ToolResult r = manager().execute("getTopItems", Map.of("type", "artists", "range", "long_term"));
        assertTrue(r.ok());
        assertEquals(List.of(new Artist("a1", "A", List.of("pop"))), r.payload());
        assertEquals(List.of("artists:long_term"), calls);
    }

    @Test
    void searchRequiresAQueryAndAcceptsALimit() {
        ToolManager tm = manager();
        assertFalse(tm.execute("search", Map.of()).ok());
        assertFalse(tm.execute("search", Map.of("query", "  ")).ok());
        assertTrue(calls.isEmpty());
        assertTrue(tm.execute("search", Map.of("query", "jazz", "limit", 5)).ok());
        assertTrue(tm.execute("search", Map.of("query", "rock", "limit", "7")).ok());
        assertEquals(List.of("search:jazz:5", "search:rock:7"), calls);
    }

    @Test
    void searchWithABadLimitFails() {
        ToolResult r = manager().execute("search", Map.of("query", "jazz", "limit", "many"));
        assertFalse(r.ok());
        assertTrue(r.error().contains("limit"));
    }

    @Test
    void searchWithoutALimitUsesTheConfiguredPageSize() {
        manager().execute("search", Map.of("query", "jazz"));
        assertEquals(List.of("search:jazz:10"), calls);
    }

    @Test
    void idArgumentsAreRequiredAndPassedThrough() {
        ToolManager tm = manager();
        assertFalse(tm.execute("getArtistAlbums", Map.of()).ok());
        assertFalse(tm.execute("getAlbumTracks", Map.of()).ok());
        assertFalse(tm.execute("getPlaylistItems", Map.of()).ok());
        tm.execute("getArtistAlbums", Map.of("artistId", "a1"));
        tm.execute("getAlbumTracks", Map.of("albumId", "al"));
        tm.execute("getPlaylistItems", Map.of("playlistId", "p1"));
        assertEquals(List.of("albums:a1", "albumTracks:al", "items:p1"), calls);
    }

    @Test
    void argumentlessOperationsRun() {
        ToolManager tm = manager();
        assertEquals(List.of(T), tm.execute("getSavedTracks", Map.of()).payload());
        assertEquals(List.of(T), tm.execute("getRecentlyPlayed", Map.of()).payload());
    }

    @Test
    void serviceErrorsBecomeFailuresWithTheMessage() {
        StubMusicService failing = new StubMusicService() {
            @Override public List<Track> getSavedTracks() { throw new MusicServiceException("Spotify down"); }
        };
        ToolManager tm = new ToolManager();
        SpotifyTool.allFor(failing).forEach(tm::register);
        ToolResult r = tm.execute("getSavedTracks", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("Spotify down"));
    }

    @Test
    void authExpiryPropagatesThroughTheManager() {
        StubMusicService expired = new StubMusicService() {
            @Override public List<Track> getRecentlyPlayed() { throw new AuthExpiredException("expired"); }
        };
        ToolManager tm = new ToolManager();
        SpotifyTool.allFor(expired).forEach(tm::register);
        assertThrows(AuthExpiredException.class, () -> tm.execute("getRecentlyPlayed", Map.of()));
    }
}
