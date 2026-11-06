package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.LastFmAdapter;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TagService;
import com.musicmind.music.StubMusicService;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateRetrieverTest {

    private final Config config = Config.of(Map.of());

    private static Track track(String id) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 1000, false, List.of("x" + id));
    }

    private static class Music extends StubMusicService {
        final List<String> searches = new ArrayList<>();
        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            return query.equals("gym workout") ? List.of(track("k1"), track("k2"), track("k1")) : List.of();
        }
    }

    private ToolManager tools(Music music) {
        ToolManager tools = new ToolManager();
        SpotifyTool.allFor(music).forEach(tools::register);
        return tools;
    }

    private static ListeningProfile profile() {
        return new ListeningProfile(Map.of(), List.of(new Artist("a1", "A1", List.of("rock"))),
                List.of(), List.of(), null, Instant.EPOCH);
    }

    @Test
    void library_neverSendsWeekToSpotifyTopItems() {
        List<String> ranges = new ArrayList<>();
        Music music = new Music() {
            @Override public List<Track> getTopItems(String type, String range) { ranges.add(range); return List.of(); }
            @Override public List<Track> getSavedTracks() { return List.of(); }
            @Override public List<Track> getRecentlyPlayed() { return List.of(); }
        };

        new CandidateRetriever(tools(music), config).library("week");

        assertEquals(List.of(config.profileDefaultRange()), ranges);
    }

    @Test
    void byKeywords_searchesThePhraseAsTyped_andRemovesDuplicates() {
        Music music = new Music();
        List<Track> found = new CandidateRetriever(tools(music), config).byKeywords("gym workout");

        assertEquals(List.of("k1", "k2"), found.stream().map(Track::id).toList());
        assertEquals(List.of("gym workout"), music.searches);
    }

    @Test
    void retrieve_addsKeywordTracks() {
        Music music = new Music();
        RecommendationEngine engine = new RecommendationEngine(tools(music), config);

        Retrieval retrieval = engine.retrieve(profile(), new PlaylistSpec(30, true, 0.5, List.of(), null),
                true, List.of(), List.of("gym workout"));

        assertTrue(music.searches.contains("gym workout"));
        Set<String> novel = new java.util.HashSet<>(retrieval.pool().novel().stream().map(Track::id).toList());
        assertEquals(Set.of("k1", "k2"), novel);
    }

    @Test
    void without_removesTracksFromEveryPoolAndFromGenresReadLater() {
        Music music = new Music();
        RecommendationEngine engine = new RecommendationEngine(tools(music), config);
        Retrieval retrieval = engine.retrieve(profile(), new PlaylistSpec(30, true, 0.5, List.of(), null),
                true, List.of(), List.of("gym workout"));

        Retrieval kept = retrieval.without(Set.of("k1", "nothing"));

        assertEquals(List.of("k2"), kept.pool().novel().stream().map(Track::id).toList());
        assertEquals(2, retrieval.pool().novel().size(), "the original retrieval is unchanged");
    }

    // ---- Last.fm tag songs matched to Spotify ----

    private static Track named(String id, String title, String... artists) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of("x" + id), List.of(artists));
    }

    /** Spotify that answers a track:"..." artist:"..." query with a fixed list. */
    private static class TagMusic extends Music {
        final Map<String, List<Track>> answers = new java.util.LinkedHashMap<>();
        final List<Integer> limits = new ArrayList<>();
        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            limits.add(limit);
            return answers.getOrDefault(query, List.of());
        }
    }

    @Test
    void byTag_matchesTitleAndArtistOnly() {
        TagMusic music = new TagMusic();
        music.answers.put("track:\"Slow Song\" artist:\"Band A\"", List.of(named("t1", "Slow Song", "Band A")));
        music.answers.put("track:\"Hello\" artist:\"Band B\"", List.of(named("t2", "HELLO!", "Guest", "band b")));
        music.answers.put("track:\"Remix Me\" artist:\"Band C\"", List.of(named("t3", "Remix Me - Remastered 2011", "Band C")));
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Slow Song", "Band A"), new TagTrack("Hello", "Band B"),
                new TagTrack("Remix Me", "Band C"));

        List<Track> found = new CandidateRetriever(tools(music), config, lastFm).byTag("slow dance");

        assertEquals(List.of("t1", "t2", "t3"), found.stream().map(Track::id).toList());
        assertEquals(List.of(1, 1, 1), music.limits);
    }

    @Test
    void byTag_dropsWrongArtistMatch() {
        TagMusic music = new TagMusic();
        music.answers.put("track:\"Slow Song\" artist:\"Band A\"", List.of(named("w1", "Slow Song", "Cover Band")));
        music.answers.put("track:\"Other\" artist:\"Band B\"", List.of(named("w2", "Other Song", "Band B")));
        music.answers.put("track:\"Nothing\" artist:\"Band C\"", List.of());
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Slow Song", "Band A"), new TagTrack("Other", "Band B"),
                new TagTrack("Nothing", "Band C"));

        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, lastFm);

        assertTrue(retriever.byTag("slow dance").isEmpty());
        assertTrue(retriever.errors().isEmpty());
    }

    @Test
    void byTag_noKey_noCalls() {
        Music music = new Music();
        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, new LastFmAdapter(config));

        assertTrue(retriever.byTag("slow dance").isEmpty());
        assertTrue(music.searches.isEmpty());
        assertTrue(retriever.errors().isEmpty());
    }

    @Test
    void byTag_capsLookupsAcrossTags() {
        TagMusic music = new TagMusic();
        Config two = config.withTunables(Map.of("lastfm.maxTracks", "3"));
        List<Integer> asked = new ArrayList<>();
        TagService lastFm = (tag, limit) -> {
            asked.add(limit);
            return List.of(new TagTrack(tag + "1", "A"), new TagTrack(tag + "2", "A"), new TagTrack(tag + "3", "A"));
        };
        CandidateRetriever retriever = new CandidateRetriever(tools(music), two, lastFm);

        retriever.byTag("a");
        retriever.byTag("b");
        retriever.byTag("c");

        assertEquals(3, music.searches.size());
        assertEquals(List.of(3), asked, "no more Last.fm calls once the lookups are used up");
    }

    @Test
    void byTag_shareLetsEveryTagContribute() {
        TagMusic music = new TagMusic();
        for (String tag : List.of("a", "b")) {
            for (int i = 1; i <= 20; i++) {
                music.answers.put("track:\"" + tag + i + "\" artist:\"A\"", List.of(named(tag + i, tag + i, "A")));
            }
        }
        TagService lastFm = (tag, limit) -> {
            List<TagTrack> all = new ArrayList<>();
            for (int i = 1; i <= 20; i++) all.add(new TagTrack(tag + i, "A"));
            return all.subList(0, Math.min(limit, all.size()));
        };
        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, lastFm);

        List<Track> first = retriever.byTag("a", 10);
        List<Track> second = retriever.byTag("b", 10);

        assertEquals(10, first.size());
        assertEquals(10, second.size());
        assertEquals(20, music.searches.size());
    }

    @Test
    void retrieve_sameTagTwice_searchesSpotifyOncePerLastFmSong() {
        TagMusic music = new TagMusic();
        music.answers.put("track:\"Slow Song\" artist:\"Band A\"", List.of(named("s1", "Slow Song", "Band A")));
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Slow Song", "Band A"), new TagTrack("Unknown", "Band Z"));
        RecommendationEngine engine = new RecommendationEngine(tools(music), config, com.musicmind.agent.ArtistGenreCache.NONE, lastFm);
        PlaylistSpec spec = new PlaylistSpec(30, true, 0.5, List.of(), null);

        Retrieval first = engine.retrieve(profile(), spec, true, List.of(), List.of(), List.of("slow dance"));
        Retrieval second = engine.retrieve(profile(), spec, true, List.of(), List.of(), List.of("slow dance"));

        long lookups = music.searches.stream().filter(q -> q.startsWith("track:")).count();
        assertEquals(2, lookups, "one search per Last.fm song, a confirmed no-match included");
        assertEquals(List.of("s1"), second.tagTracks.stream().map(Track::id).toList());
        assertEquals(first.tagTracks.stream().map(Track::id).toList(), second.tagTracks.stream().map(Track::id).toList());
    }

    @Test
    void byTag_aFailedSpotifySearchIsNotCached() {
        int[] calls = {0};
        TagMusic music = new TagMusic() {
            @Override public List<Track> search(String query, int limit) {
                if (++calls[0] == 1) throw new MusicServiceException("Spotify returned HTTP 500.");
                return List.of(named("s1", "Slow Song", "Band A"));
            }
        };
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Slow Song", "Band A"));
        java.util.Map<String, java.util.Optional<Track>> cache = new java.util.LinkedHashMap<>();
        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, lastFm, cache);

        assertTrue(retriever.byTag("x").isEmpty());
        assertEquals(List.of("s1"), retriever.byTag("x").stream().map(Track::id).toList());
        assertEquals(2, calls[0]);
    }

    @Test
    void byTag_lastfmFailure_recordedAndEmpty() {
        TagService down = (tag, limit) -> { throw new MusicServiceException("Last.fm returned HTTP 500."); };
        CandidateRetriever retriever = new CandidateRetriever(tools(new Music()), config, down);

        assertTrue(retriever.byTag("study").isEmpty());
        assertEquals(List.of("Last.fm returned HTTP 500."), retriever.errors());
    }

    @Test
    void byTag_spotifySearchFailure_recordedRestCarriesOn() {
        TagMusic music = new TagMusic() {
            @Override public List<Track> search(String query, int limit) {
                if (query.contains("Bad")) {
                    throw new com.musicmind.music.MusicServiceException("boom");
                }
                return super.search(query, limit);
            }
        };
        music.answers.put("track:\"Good\" artist:\"A\"", List.of(named("g1", "Good", "A")));
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Bad", "A"), new TagTrack("Good", "A"));
        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, lastFm);

        assertEquals(List.of("g1"), retriever.byTag("x").stream().map(Track::id).toList());
        assertEquals(1, retriever.errors().size());
    }

    @Test
    void byTag_afterOneLastFmFailureNoFurtherTagIsTried() {
        int[] calls = {0};
        TagService down = (tag, limit) -> { calls[0]++; throw new com.musicmind.music.MusicServiceException("Last.fm returned HTTP 500."); };
        CandidateRetriever retriever = new CandidateRetriever(tools(new TagMusic()), config, down);

        assertTrue(retriever.byTag("slow dance").isEmpty());
        assertTrue(retriever.byTag("romantic").isEmpty());

        assertEquals(1, calls[0]);
        assertEquals(1, retriever.errors().size());
    }

    // ---- Last.fm similar artists ----

    private static Track by(String id, String artistId, String artistName) {
        return new Track(id, "spotify:track:" + id, "Song " + id, 1000, false, List.of(artistId), List.of(artistName));
    }

    @Test
    void bySimilar_keepsOnlyExactNewArtists_andCapsSearches() {
        TagMusic music = new TagMusic();
        // Every similar name s<seed><n>; the Spotify answer is a track by exactly that artist, except two.
        TagService lastFm = new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> similarArtists(String artist, int limit) {
                assertEquals(5, limit);
                return List.of(artist + "1", artist + "2", artist + "3", artist + "4", artist + "5");
            }
        };
        for (String seed : List.of("A", "B", "C", "D")) {
            for (int n = 1; n <= 5; n++) {
                String name = seed + n;
                music.answers.put("artist:\"" + name + "\"", List.of(by("t" + name, "id" + name, name)));
            }
        }
        music.answers.put("artist:\"A2\"", List.of(by("tWrong", "idW", "Someone Else")));
        music.answers.put("artist:\"A3\"", List.of(by("tKnown", "known1", "A3")));

        List<Track> found = new CandidateRetriever(tools(music), config, lastFm)
                .bySimilar(List.of("A", "B", "C", "D"), Set.of("known1"), 10);

        assertEquals(10, music.searches.size(), music.searches.toString());
        assertTrue(music.limits.stream().allMatch(l -> l == 3), music.limits.toString());
        assertTrue(music.searches.stream().noneMatch(q -> q.contains("D")), "only the first 3 seeds are used");
        assertEquals(List.of("tA1", "tA4", "tA5", "tB1", "tB2", "tB3", "tB4", "tB5"),
                found.stream().map(Track::id).toList()); // A2 wrong artist and A3 known artist dropped; C never searched
    }

    @Test
    void bySimilar_stopsAtTheFirstRateLimit_andSkipsTheTopArtistsThemselves() {
        TagMusic music = new TagMusic() {
            @Override public List<Track> search(String query, int limit) {
                searches.add(query);
                throw new com.musicmind.music.RateLimitException("slow down", 5);
            }
        };
        TagService lastFm = new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> similarArtists(String artist, int limit) { return List.of("Mine", "New1", "New2"); }
        };
        CandidateRetriever retriever = new CandidateRetriever(tools(music), config, lastFm);

        List<Track> found = retriever.bySimilar(List.of("Mine", "Other"), Set.of(), 10);

        assertTrue(found.isEmpty());
        assertEquals(List.of("artist:\"New1\""), music.searches, "a top artist is never searched; the rate limit ends the run");
        assertFalse(retriever.errors().isEmpty());
    }

    @Test
    void bySimilar_failedSearchIsNotCached_butAGoodOneIs() {
        int[] calls = {0};
        TagMusic music = new TagMusic() {
            @Override public List<Track> search(String query, int limit) {
                searches.add(query);
                if (query.contains("Bad") && calls[0]++ == 0) {
                    throw new MusicServiceException("boom");
                }
                return query.contains("Bad") ? List.of(by("tBad", "idBad", "Bad")) : List.of(by("tGood", "idGood", "Good"));
            }
        };
        TagService lastFm = new TagService() {
            @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
            @Override public List<String> similarArtists(String artist, int limit) { return List.of("Bad", "Good"); }
        };
        Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
        Map<String, List<Track>> hits = new java.util.LinkedHashMap<>();
        for (int run = 0; run < 2; run++) {
            new CandidateRetriever(tools(music), config, lastFm, new java.util.LinkedHashMap<>(), lists, hits)
                    .bySimilar(List.of("Seed"), Set.of(), 10);
        }
        assertEquals(List.of("artist:\"Bad\"", "artist:\"Good\"", "artist:\"Bad\""), music.searches);
    }
}
