package com.musicmind.agent;

import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.RateLimitException;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns Last.fm songs into real Spotify tracks, shared by every feature that starts from Last.fm songs (Create's
 * tag pool and Find Songs). Each song is looked up with one Spotify search (limit 1,
 * {@code track:"title" artist:"artist"}) and kept only when the normalised title and one artist name both match;
 * anything else is dropped, never replaced by a loose match. Results of finished lookups, including "no match",
 * are kept in the session cache so a repeat costs no search; a failed search is never cached. The first
 * rate-limit or expired-login answer ends the run, since every further search would fail the same way.
 */
public final class TagTrackMatcher {

    /**
     * @param tracks   the matched Spotify tracks, in the order of the wanted songs, de-duplicated by id
     * @param errors   messages of the searches that failed
     * @param stopped  true when matching ended early on a rate limit or an expired login
     * @param searched how many Spotify searches succeeded (an empty answer counts)
     */
    public record Match(List<Track> tracks, List<String> errors, boolean stopped, int searched) { }

    private final ToolManager tools;
    private final Map<String, Optional<Track>> sessionCache;

    public TagTrackMatcher(ToolManager tools, Map<String, Optional<Track>> sessionCache) {
        this.tools = tools;
        this.sessionCache = sessionCache;
    }

    /**
     * Looks up the first {@code maxSearches} of {@code wanted}. A cache hit makes no search but still counts
     * toward {@code maxSearches}.
     */
    public Match match(List<TagTrack> wanted, int maxSearches) {
        Map<String, Track> byId = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        boolean stopped = false;
        int searched = 0;
        int used = 0;
        for (TagTrack song : wanted) {
            if (used >= maxSearches) {
                break;
            }
            used++;
            String key = SearchRanker.normalise(song.title()) + "|" + SearchRanker.normalise(song.artist());
            Optional<Track> found = sessionCache.get(key);
            if (found == null) {
                String query = "track:\"" + song.title().replace("\"", " ") + "\" artist:\""
                        + song.artist().replace("\"", " ") + "\"";
                ToolResult result;
                try {
                    result = tools.execute("search", Map.of("query", query, "limit", 1));
                } catch (AuthExpiredException e) {
                    errors.add(e.getMessage());
                    stopped = true;
                    break;
                }
                if (!result.ok()) {
                    errors.add(result.error());
                    if (result.cause() instanceof RateLimitException || result.cause() instanceof AuthExpiredException) {
                        stopped = true;
                        break;
                    }
                    continue;
                }
                searched++;
                @SuppressWarnings("unchecked")
                List<Track> hits = (List<Track>) result.payload();
                found = hits.stream().filter(hit -> matches(song, hit)).findFirst();
                sessionCache.put(key, found);
            }
            found.ifPresent(hit -> byId.putIfAbsent(hit.id(), hit));
        }
        return new Match(new ArrayList<>(byId.values()), errors, stopped, searched);
    }

    private static boolean matches(TagTrack wanted, Track hit) {
        String title = SearchRanker.normalise(wanted.title());
        String artist = SearchRanker.normalise(wanted.artist());
        if (title.isEmpty() || artist.isEmpty() || hit.title() == null) {
            return false;
        }
        boolean titleOk = SearchRanker.normalise(hit.title()).equals(title)
                || SearchRanker.normalise(hit.title().replaceAll("\\([^)]*\\)|\\[[^]]*]| - .*$", "")).equals(title);
        return titleOk && hit.artistNames().stream().anyMatch(n -> SearchRanker.normalise(n).equals(artist));
    }
}
