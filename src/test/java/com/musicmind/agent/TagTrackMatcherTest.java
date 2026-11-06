package com.musicmind.agent;

import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.RateLimitException;
import com.musicmind.music.StubMusicService;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagTrackMatcherTest {

    private static Track named(String id, String title, String... artists) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of("x" + id), List.of(artists));
    }

    private static class Music extends StubMusicService {
        final Map<String, List<Track>> answers = new LinkedHashMap<>();
        final List<String> searches = new ArrayList<>();
        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            return answers.getOrDefault(query, List.of());
        }
    }

    private static ToolManager tools(Music music) {
        ToolManager tools = new ToolManager();
        SpotifyTool.allFor(music).forEach(tools::register);
        return tools;
    }

    @Test
    void matchesTitleAndArtist_dropsWrongArtist() {
        Music music = new Music();
        music.answers.put("track:\"Slow Song\" artist:\"Band A\"", List.of(named("t1", "Slow Song - Remastered", "Band A")));
        music.answers.put("track:\"Other\" artist:\"Band B\"", List.of(named("w1", "Other", "Cover Band")));

        TagTrackMatcher.Match match = new TagTrackMatcher(tools(music), new LinkedHashMap<>()).match(
                List.of(new TagTrack("Slow Song", "Band A"), new TagTrack("Other", "Band B")), 10);

        assertEquals(List.of("t1"), match.tracks().stream().map(Track::id).toList());
        assertTrue(match.errors().isEmpty());
        assertFalse(match.stopped());
    }

    @Test
    void stopsAtRateLimit_keepsEarlierMatches() {
        Music music = new Music() {
            @Override public List<Track> search(String query, int limit) {
                searches.add(query);
                if (searches.size() == 2) throw new RateLimitException("slow down", 5);
                return List.of(named("t1", "One", "A"));
            }
        };

        TagTrackMatcher.Match match = new TagTrackMatcher(tools(music), new LinkedHashMap<>()).match(
                List.of(new TagTrack("One", "A"), new TagTrack("Two", "A"), new TagTrack("Three", "A")), 10);

        assertEquals(2, music.searches.size(), "the third song is never searched");
        assertTrue(match.stopped());
        assertEquals(List.of("t1"), match.tracks().stream().map(Track::id).toList());
        assertEquals(1, match.errors().size());
    }

    @Test
    void cacheHitMakesNoSearch() {
        Music music = new Music();
        Map<String, Optional<Track>> cache = new LinkedHashMap<>();
        cache.put("one|a", Optional.of(named("c1", "One", "A")));

        TagTrackMatcher.Match match = new TagTrackMatcher(tools(music), cache).match(
                List.of(new TagTrack("One", "A"), new TagTrack("Two", "A")), 1);

        assertTrue(music.searches.isEmpty(), "a hit costs no search and still uses one of the maxSearches");
        assertEquals(List.of("c1"), match.tracks().stream().map(Track::id).toList());
    }
}
