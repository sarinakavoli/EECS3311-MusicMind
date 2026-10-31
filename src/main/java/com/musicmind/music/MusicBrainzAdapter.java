package com.musicmind.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import com.musicmind.domain.Artist;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adapter (GoF) from the MusicBrainz web service to {@link GenreService}. An artist
 * is looked up by name; a hit is accepted only if its name (or a listed alias)
 * equals the Spotify name ignoring case and accents and its score reaches the
 * configured minimum. Anything less certain yields no genres: it never guesses.
 * Requests are paced to MusicBrainz's one-per-second limit across all lookups, a
 * 503 is retried once, and every other failure yields no genres plus a notice.
 */
public class MusicBrainzAdapter implements GenreService {

    /** Seam for pacing: the current time in milliseconds. */
    interface Clock {
        long millis();
    }

    /** Seam so tests can verify pacing and retry waits without really sleeping. */
    interface Sleeper {
        void sleepMillis(long millis) throws InterruptedException;
    }

    /** A failed call; carries the wording for the notice. */
    private static final class LookupFailed extends Exception {
        LookupFailed(String message) {
            super(message);
        }
    }

    private final Config config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final Clock clock;
    private final Sleeper sleeper;
    private final List<String> notices = new CopyOnWriteArrayList<>();
    /** Time the last request was sent; guarded by {@code this}. */
    private long lastRequestMillis;
    private boolean anyRequestSent;

    public MusicBrainzAdapter(Config config) {
        this(config, System::currentTimeMillis, Thread::sleep);
    }

    MusicBrainzAdapter(Config config, Clock clock, Sleeper sleeper) {
        this.config = config;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** What went wrong in lookups so far, one notice per failed artist. */
    public List<String> notices() {
        return List.copyOf(notices);
    }

    @Override
    public Optional<List<String>> lookup(Artist artist) {
        String name = artist.name() == null ? "" : artist.name().strip();
        if (name.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            String mbid = findMatch(name);
            return Optional.of(mbid == null ? List.of() : genresOf(mbid));
        } catch (LookupFailed e) {
            notices.add(String.format(config.wording("genreNoticeFailed"), name, e.getMessage()));
            return Optional.empty();
        }
    }

    private String findMatch(String name) throws LookupFailed {
        String query = "artist:\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String url = config.musicBrainzBaseUrl() + "/artist/?query=" + encode(query)
                + "&limit=" + config.musicBrainzResultLimit() + "&fmt=json";
        JsonArray artists = get(url).getAsJsonArray("artists");
        if (artists == null) {
            return null;
        }
        String wanted = normalise(name);
        for (JsonElement e : artists) {
            JsonObject candidate = e.getAsJsonObject();
            int score = candidate.has("score") ? candidate.get("score").getAsInt() : 0;
            if (score >= config.musicBrainzMinScore() && namesMatch(wanted, candidate)) {
                return candidate.get("id").getAsString();
            }
        }
        return null;
    }

    private static boolean namesMatch(String wanted, JsonObject candidate) {
        if (candidate.has("name") && normalise(candidate.get("name").getAsString()).equals(wanted)) {
            return true;
        }
        if (candidate.has("aliases") && candidate.get("aliases").isJsonArray()) {
            for (JsonElement alias : candidate.getAsJsonArray("aliases")) {
                JsonObject a = alias.getAsJsonObject();
                if (a.has("name") && normalise(a.get("name").getAsString()).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> genresOf(String mbid) throws LookupFailed {
        JsonArray genres = get(config.musicBrainzBaseUrl() + "/artist/" + encode(mbid) + "?inc=genres&fmt=json")
                .getAsJsonArray("genres");
        if (genres == null) {
            return List.of();
        }
        List<JsonObject> sorted = new ArrayList<>();
        genres.forEach(g -> sorted.add(g.getAsJsonObject()));
        sorted.sort(Comparator.comparingInt((JsonObject g) -> g.has("count") ? g.get("count").getAsInt() : 0).reversed());
        return sorted.stream().map(g -> g.get("name").getAsString()).toList();
    }

    /** GET with pacing, one retry on 503, and every failure reported as {@link LookupFailed}. */
    private JsonObject get(String url) throws LookupFailed {
        try {
            HttpResponse<String> response = send(url);
            if (response.statusCode() == 503) {
                long wait = response.headers().firstValue("Retry-After").map(MusicBrainzAdapter::seconds)
                        .orElse((long) config.musicBrainzRetryWaitSeconds());
                if (wait > config.musicBrainzMaxWaitSeconds()) {
                    throw new LookupFailed("MusicBrainz is rate limiting requests (HTTP 503) and asked for a "
                            + wait + " second wait.");
                }
                sleeper.sleepMillis(wait * 1000L);
                response = send(url);
            }
            if (response.statusCode() == 503) {
                throw new LookupFailed("MusicBrainz is rate limiting requests (HTTP 503).");
            }
            if (response.statusCode() / 100 != 2) {
                throw new LookupFailed("HTTP " + response.statusCode() + " " + config.truncateBody(response.body()));
            }
            JsonElement parsed = gson.fromJson(response.body(), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new LookupFailed("unreadable response");
            }
            return parsed.getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LookupFailed("interrupted");
        } catch (IOException | RuntimeException e) {
            throw new LookupFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private HttpResponse<String> send(String url) throws IOException, InterruptedException {
        pace();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", config.musicBrainzUserAgent())
                .header("Accept", "application/json")
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Blocks until the minimum interval since the previous request (from any lookup) has passed. */
    private synchronized void pace() throws InterruptedException {
        if (anyRequestSent) {
            long wait = config.musicBrainzMinIntervalMillis() - (clock.millis() - lastRequestMillis);
            if (wait > 0) {
                sleeper.sleepMillis(wait);
            }
        }
        anyRequestSent = true;
        lastRequestMillis = clock.millis();
    }

    private static long seconds(String retryAfter) {
        try {
            return Long.parseLong(retryAfter.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Lower-case, accent-free, trimmed form used to compare names. */
    private static String normalise(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).strip();
    }
}
