package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
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
}
