package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.Track;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter (GoF) that implements the internal {@link MusicService} over the
 * Spotify Web API. It translates between Spotify's JSON shapes and the app's
 * domain objects, so the rest of the system never depends on Spotify directly.
 * Authentication (PKCE) is delegated to {@link SpotifyAuth}.
 *
 * <p>Dev-mode Spotify constraints are respected: discovery is built from the
 * user's own data (top items, saved, recently played, search, artist→albums→
 * tracks); {@code /recommendations} and audio-features are not used.
 */
public class SpotifyAdapter implements MusicService {

    private static final String API = "https://api.spotify.com/v1";

    private final SpotifyAuth auth;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private String cachedUserId;

    public SpotifyAdapter(String clientId, String redirectUri) {
        this(new SpotifyAuth(clientId, redirectUri));
    }

    public SpotifyAdapter(SpotifyAuth auth) {
        this.auth = auth;
    }

    // ---- read: listening data ----

    @Override
    public List<Artist> getTopArtists(String timeRange) {
        JsonObject body = get("/me/top/artists?limit=20&time_range=" + range(timeRange));
        List<Artist> artists = new ArrayList<>();
        for (JsonElement e : array(body, "items")) {
            artists.add(toArtist(e.getAsJsonObject()));
        }
        return artists;
    }

    @Override
    public List<Track> getTopItems(String type, String timeRange) {
        String kind = (type == null || type.isBlank()) ? "tracks" : type.toLowerCase();
        JsonObject body = get("/me/top/" + kind + "?limit=20&time_range=" + range(timeRange));
        return tracks(array(body, "items"));
    }

    @Override
    public List<Track> getSavedTracks() {
        JsonObject body = get("/me/tracks?limit=50");
        return tracksFromWrapped(array(body, "items"), "track");
    }

    @Override
    public List<Track> getRecentlyPlayed() {
        JsonObject body = get("/me/player/recently-played?limit=50");
        return tracksFromWrapped(array(body, "items"), "track");
    }

    // ---- read: catalogue ----

    @Override
    public List<Track> search(String query, int limit) {
        int capped = Math.max(1, Math.min(limit, 10)); // dev-mode search cap
        JsonObject body = get("/search?type=track&limit=" + capped + "&q=" + enc(query));
        JsonObject tracks = body.has("tracks") ? body.getAsJsonObject("tracks") : new JsonObject();
        return tracks(array(tracks, "items"));
    }

    @Override
    public List<Album> getArtistAlbums(String artistId) {
        JsonObject body = get("/artists/" + enc(artistId) + "/albums?limit=50&include_groups=album,single");
        List<Album> albums = new ArrayList<>();
        for (JsonElement e : array(body, "items")) {
            JsonObject a = e.getAsJsonObject();
            albums.add(new Album(str(a, "id"), str(a, "name"), str(a, "release_date")));
        }
        return albums;
    }

    @Override
    public List<Track> getAlbumTracks(String albumId) {
        JsonObject body = get("/albums/" + enc(albumId) + "/tracks?limit=50");
        return tracks(array(body, "items"));
    }

    // ---- write: playlists ----

    @Override
    public Playlist createPlaylist(String userId, String name) {
        JsonObject req = new JsonObject();
        req.addProperty("name", name);
        req.addProperty("public", false);
        req.addProperty("description", "Created by MusicMind");
        JsonObject body = send("POST", "/users/" + enc(userId) + "/playlists", req.toString());
        return new Playlist(str(body, "id"), str(body, "name"), List.of());
    }

    @Override
    public void addItems(String playlistId, List<String> uris) {
        JsonObject req = new JsonObject();
        req.add("uris", gson.toJsonTree(uris));
        send("POST", "/playlists/" + enc(playlistId) + "/tracks", req.toString());
    }

    @Override
    public void removeItems(String playlistId, List<String> uris) {
        JsonArray tracks = new JsonArray();
        for (String uri : uris) {
            JsonObject t = new JsonObject();
            t.addProperty("uri", uri);
            tracks.add(t);
        }
        JsonObject req = new JsonObject();
        req.add("tracks", tracks);
        send("DELETE", "/playlists/" + enc(playlistId) + "/tracks", req.toString());
    }

    @Override
    public void reorderItems(String playlistId, int from, int to) {
        JsonObject req = new JsonObject();
        req.addProperty("range_start", from);
        req.addProperty("insert_before", to);
        req.addProperty("range_length", 1);
        send("PUT", "/playlists/" + enc(playlistId) + "/tracks", req.toString());
    }

    @Override
    public List<Track> getPlaylistItems(String playlistId) {
        JsonObject body = get("/playlists/" + enc(playlistId) + "/tracks?limit=100");
        return tracksFromWrapped(array(body, "items"), "track");
    }

    @Override
    public String currentUserId() {
        if (cachedUserId == null) {
            cachedUserId = str(get("/me"), "id");
        }
        return cachedUserId;
    }

    // ---- HTTP plumbing ----

    private JsonObject get(String path) {
        return send("GET", path, null);
    }

    private JsonObject send(String method, String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API + path))
                .header("Authorization", "Bearer " + auth.accessToken());
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 429) {
                String retry = response.headers().firstValue("Retry-After").orElse("1");
                throw new RateLimitException("Spotify rate limit hit on " + method + " " + path,
                        parseInt(retry, 1));
            }
            if (code / 100 != 2) {
                throw new MusicServiceException("Spotify API error (HTTP " + code + ") on "
                        + method + " " + path + ": " + response.body(), code);
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return new JsonObject(); // 204 No Content (e.g. reorder)
            }
            JsonElement parsed = gson.fromJson(body, JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (MusicServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MusicServiceException("Spotify request failed on " + method + " " + path, e);
        }
    }

    // ---- JSON -> domain mapping ----

    private List<Track> tracks(JsonArray items) {
        List<Track> out = new ArrayList<>();
        for (JsonElement e : items) {
            out.add(toTrack(e.getAsJsonObject()));
        }
        return out;
    }

    /** For responses where each item wraps the track under a key (saved/recent/playlist). */
    private List<Track> tracksFromWrapped(JsonArray items, String key) {
        List<Track> out = new ArrayList<>();
        for (JsonElement e : items) {
            JsonObject item = e.getAsJsonObject();
            if (item.has(key) && item.get(key).isJsonObject()) {
                out.add(toTrack(item.getAsJsonObject(key)));
            }
        }
        return out;
    }

    private Track toTrack(JsonObject t) {
        List<String> artistIds = new ArrayList<>();
        if (t.has("artists")) {
            for (JsonElement a : t.getAsJsonArray("artists")) {
                artistIds.add(str(a.getAsJsonObject(), "id"));
            }
        }
        return new Track(
                str(t, "id"),
                str(t, "uri"),
                str(t, "name"),
                t.has("duration_ms") ? t.get("duration_ms").getAsInt() : 0,
                t.has("explicit") && t.get("explicit").getAsBoolean(),
                artistIds);
    }

    private Artist toArtist(JsonObject a) {
        List<String> genres = new ArrayList<>();
        if (a.has("genres")) {
            for (JsonElement g : a.getAsJsonArray("genres")) {
                genres.add(g.getAsString());
            }
        }
        return new Artist(str(a, "id"), str(a, "name"), genres);
    }

    // ---- small JSON helpers ----

    private static JsonArray array(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** Map a friendly range to Spotify's {@code time_range} values. */
    private static String range(String timeRange) {
        if (timeRange == null) return "medium_term";
        return switch (timeRange.toLowerCase()) {
            case "short", "short_term", "recent" -> "short_term";
            case "long", "long_term", "all", "all_time" -> "long_term";
            default -> "medium_term";
        };
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
