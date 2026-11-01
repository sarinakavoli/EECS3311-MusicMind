package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRankerTest {

    private final SearchRanker ranker = new SearchRanker(Config.of(Map.of()));

    private static Track t(String id, String title) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of());
    }

    private static SearchQuery q(String keywords) {
        return new SearchQuery(keywords, null, Map.of(), 0.9);
    }

    @Test
    void aTitleMatchingMoreOfTheQueryRanksHigherAmongNeighbours() {
        // A full-page result list: adjacent hits differ little in Spotify's order, so a title match swaps them.
        List<Track> results = new ArrayList<>();
        results.add(t("a", "Sunshine"));
        results.add(t("b", "Mellow Rain"));
        for (int i = 2; i < 20; i++) {
            results.add(t("x" + i, "Other " + i));
        }

        List<Track> ranked = ranker.rank(q("mellow rain"), results);

        assertEquals(List.of("b", "a", "x2"), ranked.stream().limit(3).map(Track::id).toList());
    }

    @Test
    void spotifysRelevanceOrderLeadsAndATitleMatchIsOnlyASmallBoost() {
        // Live finding: a mood search put stock-music titles that repeat the query words above Spotify's own top hits.
        List<Track> results = new ArrayList<>();
        results.add(t("top", "Nights In Tokyo"));
        for (int i = 1; i < 19; i++) {
            results.add(t("m" + i, "Other " + i));
        }
        results.add(t("stock", "Calm Relaxing Study Music"));

        List<Track> ranked = ranker.rank(q("calm relaxing study music"), results);

        assertEquals("top", ranked.get(0).id(), "Spotify's first result stays first");
        assertTrue(ranked.indexOf(ranked.stream().filter(x -> x.id().equals("stock")).findFirst().orElseThrow()) > 10,
                "a title match at the bottom of Spotify's list does not jump to the top");
    }

    @Test
    void theShippedWeightsPutSpotifyOrderWellAboveTitleMatch() {
        Config c = Config.of(Map.of());
        assertTrue(c.searchSpotifyOrderWeight() >= 4 * c.searchTitleMatchWeight(),
                "title match is a small boost: " + c.searchTitleMatchWeight() + " vs " + c.searchSpotifyOrderWeight());
    }

    @Test
    void whenTitlesMatchEquallySpotifysOrderDecides() {
        List<Track> ranked = ranker.rank(q("xyz"), List.of(t("z", "One"), t("a", "Two"), t("m", "Three")));
        assertEquals(List.of("z", "a", "m"), ranked.stream().map(Track::id).toList());
    }

    @Test
    void theSameInputAlwaysGivesTheSameOutput() {
        List<Track> in = new ArrayList<>(List.of(t("a", "Rain"), t("b", "Rain"), t("c", "Mellow"), t("d", "Rain Rain")));
        List<Track> first = ranker.rank(q("rain"), in);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, ranker.rank(q("rain"), new ArrayList<>(in)));
        }
        Collections.reverse(in);
        assertEquals(in.size(), ranker.rank(q("rain"), in).size());
    }

    @Test
    void noKeywordsLeavesSpotifysOrder() {
        SearchQuery genreOnly = new SearchQuery("", "jazz", Map.of(), 0.9);
        List<Track> ranked = ranker.rank(genreOnly, List.of(t("b", "B"), t("a", "A")));
        assertEquals(List.of("b", "a"), ranked.stream().map(Track::id).toList());
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        assertEquals(List.of(), ranker.rank(q("x"), List.of()));
    }
}
