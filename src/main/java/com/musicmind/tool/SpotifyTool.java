package com.musicmind.tool;

import com.musicmind.config.Config;
import com.musicmind.music.MusicService;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One Spotify operation exposed to the agent by name. Each instance wraps a single
 * {@link MusicService} call; {@link #allFor} creates the full set. Missing or
 * malformed arguments produce a failure result, never an exception.
 */
public class SpotifyTool implements Tool {

    private final String name;
    private final Function<Map<String, Object>, Object> operation;

    private SpotifyTool(String name, Function<Map<String, Object>, Object> operation) {
        this.name = name;
        this.operation = operation;
    }

    /** The read tools: getTopItems, getSavedTracks, getRecentlyPlayed, search, searchArtists, getArtistAlbums, getAlbumTracks, getPlaylistItems. */
    public static List<Tool> allFor(MusicService music) {
        int defaultLimit = Config.defaults().spotifySearchPageSize();
        return List.of(
                new SpotifyTool("getTopItems", a -> {
                    String range = optional(a, "range");
                    String type = optional(a, "type");
                    if (type == null || "tracks".equalsIgnoreCase(type)) {
                        return music.getTopItems("tracks", range);
                    }
                    if ("artists".equalsIgnoreCase(type)) {
                        return music.getTopArtists(range);
                    }
                    throw new IllegalArgumentException("'type' must be 'tracks' or 'artists', got '" + type + "'");
                }),
                new SpotifyTool("getSavedTracks", a -> music.getSavedTracks()),
                new SpotifyTool("getRecentlyPlayed", a -> music.getRecentlyPlayed()),
                new SpotifyTool("search", a -> music.search(required(a, "query"), limit(a, defaultLimit))),
                new SpotifyTool("searchArtists", a -> music.searchArtists(required(a, "query"), limit(a, defaultLimit))),
                new SpotifyTool("getArtistAlbums", a -> music.getArtistAlbums(required(a, "artistId"))),
                new SpotifyTool("getAlbumTracks", a -> music.getAlbumTracks(required(a, "albumId"))),
                new SpotifyTool("getPlaylistItems", a -> music.getPlaylistItems(required(a, "playlistId"))));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            return ToolResult.of(operation.apply(args));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(name + ": " + e.getMessage());
        }
    }

    private static String optional(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String required(Map<String, Object> args, String key) {
        String v = optional(args, key);
        if (v == null) {
            throw new IllegalArgumentException("missing required argument '" + key + "'");
        }
        return v;
    }

    private static int limit(Map<String, Object> args, int fallback) {
        String v = optional(args, "limit");
        if (v == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < 1) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'limit' must be a positive whole number, got '" + v + "'");
        }
    }
}
