package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.TagTrack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Adapter (GoF) from Last.fm's {@code tag.gettoptracks}, {@code track.gettoptags} and {@code artist.gettoptags} to
 * {@link TagService} (song tags) and {@link GenreService} (artist tags, which the app shows as genres). With no API key
 * it returns an empty answer without calling. The key is only ever in the request URL; it is never
 * logged or put into an exception message. Junk tags are dropped by {@link TagFilter}. After too many failed artist
 * lookups in a row it stops looking for a cooldown, so a run finishes quickly during an outage.
 */
public class LastFmAdapter implements GenreService, TagService {

    private final Config config;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final LongSupplier clock;
    /** Failed artist lookups in a row, and when lookups may resume after too many; guarded by {@code this}. */
    private int consecutiveFailures;
    private long pausedUntilMillis;

    public LastFmAdapter(Config config) {
        this(config, System::currentTimeMillis);
    }

    LastFmAdapter(Config config, LongSupplier clock) {
        this.clock = clock;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.lastfmTimeoutSeconds())).build();
    }

    @Override
    public List<TagTrack> topTracks(String tag, int limit) {
        if (!config.lastfmConfigured() || tag == null || tag.isBlank() || limit <= 0) {
            return List.of();
        }
        String url = config.lastfmApiBaseUrl() + "?method=tag.gettoptracks&tag=" + encode(tag.strip())
                + "&limit=" + limit + "&api_key=" + encode(config.lastfmApiKey()) + "&format=json";
        return parse(fetch(url), limit);
    }

    @Override
    public List<String> trackTags(String artist, String title) {
        if (!config.lastfmConfigured() || artist == null || artist.isBlank() || title == null || title.isBlank()) {
            return List.of();
        }
        String url = config.lastfmApiBaseUrl() + "?method=track.gettoptags&artist=" + encode(artist.strip())
                + "&track=" + encode(title.strip()) + "&autocorrect=1&api_key=" + encode(config.lastfmApiKey())
                + "&format=json";
        return parseTags(fetch(url), artist, config.lastfmTrackTagsKept());
    }

    /**
     * The artist's top Last.fm tags, filtered. A present list (even empty, including "artist not found") is a real
     * answer; an empty Optional means the lookup failed, was skipped during a cooldown, or no key is set.
     */
    @Override
    public Optional<List<String>> lookup(Artist artist) {
        String name = artist == null || artist.name() == null ? "" : artist.name().strip();
        if (!config.lastfmConfigured()) {
            return Optional.empty();
        }
        if (name.isEmpty()) {
            return Optional.of(List.of());
        }
        if (paused()) {
            return Optional.empty(); // unknown, like a failure: not cached
        }
        String url = config.lastfmApiBaseUrl() + "?method=artist.gettoptags&artist=" + encode(name)
                + "&autocorrect=1&api_key=" + encode(config.lastfmApiKey()) + "&format=json";
        try {
            List<String> tags = parseTags(fetch(url), name, config.lastfmArtistTagsKept());
            recordSuccess();
            return Optional.of(tags);
        } catch (MusicServiceException e) {
            recordFailure();
            return Optional.empty();
        }
    }

    private synchronized boolean paused() {
        return clock.getAsLong() < pausedUntilMillis;
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** At the failure limit lookups pause for the cooldown; one failure after it ends starts the next pause. */
    private synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= config.lastfmMaxConsecutiveFailures()) {
            pausedUntilMillis = clock.getAsLong() + config.lastfmCooldownSeconds() * 1000L;
        }
    }

    /** One GET; any failure becomes a {@link MusicServiceException} that names neither the URL, the key nor the song. */
    private String fetch(String url) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", config.lastfmUserAgent())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(config.lastfmTimeoutSeconds()))
                    .GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MusicServiceException("Last.fm request was interrupted.");
        } catch (IOException | RuntimeException e) {
            // The exception text could echo the URL (and so the key); report only its type.
            throw new MusicServiceException("Last.fm could not be reached (" + e.getClass().getSimpleName() + ").");
        }
        if (response.statusCode() / 100 != 2) {
            throw new MusicServiceException("Last.fm returned HTTP " + response.statusCode() + ".",
                    response.statusCode());
        }
        return response.body();
    }

    /** Filtered tag names in Last.fm's order, at most {@code keep}; "not found" (error 6) is an empty list. */
    private List<String> parseTags(String body, String artistName, int keep) {
        try {
            JsonElement parsed = gson.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new MusicServiceException("Last.fm reply could not be read.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("error")) {
                if (root.get("error").getAsInt() == 6) {
                    return List.of();
                }
                String message = root.has("message") && root.get("message").isJsonPrimitive()
                        ? config.truncateBody(root.get("message").getAsString()) : "";
                throw new MusicServiceException("Last.fm reported an error (" + root.get("error").getAsString()
                        + ") " + message);
            }
            JsonObject top = root.has("toptags") && root.get("toptags").isJsonObject()
                    ? root.getAsJsonObject("toptags") : null;
            JsonElement list = top == null ? null : top.get("tag");
            if (list == null) {
                return List.of();
            }
            JsonArray elements = list.isJsonArray() ? list.getAsJsonArray() : new JsonArray();
            if (list.isJsonObject()) {
                elements.add(list);
            }
            return TagFilter.clean(elements, artistName, keep, config);
        } catch (MusicServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MusicServiceException("Last.fm reply could not be read.");
        }
    }

    private List<TagTrack> parse(String body, int limit) {
        try {
            JsonElement parsed = gson.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new MusicServiceException("Last.fm reply could not be read.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("error")) {
                String message = root.has("message") && root.get("message").isJsonPrimitive()
                        ? config.truncateBody(root.get("message").getAsString()) : "";
                throw new MusicServiceException("Last.fm reported an error (" + root.get("error").getAsString()
                        + ") " + message);
            }
            JsonObject tracks = root.has("tracks") && root.get("tracks").isJsonObject()
                    ? root.getAsJsonObject("tracks") : null;
            if (tracks == null) {
                throw new MusicServiceException("Last.fm reply has no tracks list.");
            }
            List<TagTrack> out = new ArrayList<>();
            JsonElement list = tracks.get("track");
            if (list == null) {
                return out; // an unknown or empty tag
            }
            // Last.fm sends an object, not an array, when there is exactly one result.
            JsonArray elements = list.isJsonArray() ? list.getAsJsonArray() : new JsonArray();
            if (list.isJsonObject()) {
                elements.add(list);
            }
            for (JsonElement e : elements) {
                TagTrack track = tagTrack(e);
                if (track != null) { // a malformed element is skipped, not fatal
                    out.add(track);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            return out;
        } catch (MusicServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MusicServiceException("Last.fm reply could not be read.");
        }
    }

    private static TagTrack tagTrack(JsonElement e) {
        try {
            JsonObject t = e.getAsJsonObject();
            String title = t.get("name").getAsString().strip();
            JsonElement artist = t.get("artist");
            String artistName = (artist.isJsonObject() ? artist.getAsJsonObject().get("name") : artist)
                    .getAsString().strip();
            return title.isEmpty() || artistName.isEmpty() ? null : new TagTrack(title, artistName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
