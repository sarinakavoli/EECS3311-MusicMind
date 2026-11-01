package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.Track;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the candidate pools for F4 through the {@link ToolManager}: the user's own tracks (top,
 * saved, recently played), tracks found by a genre search, and album tracks of given artists.
 * A tool that fails does not stop the others: its message is kept in {@link #errors()}.
 * Results are returned in the order Spotify gave them, de-duplicated by track id.
 */
class CandidateRetriever {

    private final ToolManager tools;
    private final Config config;
    private final List<String> errors = new ArrayList<>();
    private int successes;

    CandidateRetriever(ToolManager tools, Config config) {
        this.tools = tools;
        this.config = config;
    }

    /** Top tracks for the range, then saved tracks, then recently played. */
    List<Track> library(String range) {
        Map<String, Track> byId = new LinkedHashMap<>();
        addAll(byId, read("getTopItems", Map.of("type", "tracks", "range", range)));
        addAll(byId, read("getSavedTracks", Map.of()));
        addAll(byId, read("getRecentlyPlayed", Map.of()));
        return new ArrayList<>(byId.values());
    }

    /** Tracks Spotify's search returns for the genre, sent as a plain keyword (R19). */
    List<Track> byGenre(String genre) {
        String query = new SearchQuery(null, genre, Map.of(), 1.0).toSpotifyQuery();
        Map<String, Track> byId = new LinkedHashMap<>();
        addAll(byId, read("search", Map.of("query", query, "limit", config.discoverSearchLimit())));
        return new ArrayList<>(byId.values());
    }

    /** Tracks of the first few albums of each artist. */
    List<Track> albumTracks(List<String> artistIds) {
        Map<String, Track> byId = new LinkedHashMap<>();
        for (String artistId : artistIds) {
            List<Album> albums = read("getArtistAlbums", Map.of("artistId", artistId));
            for (Album album : albums.stream().limit(config.discoverAlbumsPerArtist()).toList()) {
                addAll(byId, read("getAlbumTracks", Map.of("albumId", album.id())));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Messages of the reads that failed. */
    List<String> errors() {
        return errors;
    }

    /** How many reads succeeded (an empty answer counts). */
    int successes() {
        return successes;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> read(String tool, Map<String, Object> args) {
        ToolResult result = tools.execute(tool, args);
        if (!result.ok()) {
            errors.add(result.error());
            return List.of();
        }
        successes++;
        return (List<T>) result.payload();
    }

    private static void addAll(Map<String, Track> byId, List<Track> tracks) {
        tracks.forEach(t -> byId.putIfAbsent(t.id(), t));
    }
}
