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

class SearchRankerTest {

    private final SearchRanker ranker = new SearchRanker(Config.of(Map.of()));

    private static Track t(String id, String title) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of());
    }

    private static SearchQuery q(String keywords) {
        return new SearchQuery(keywords, null, Map.of(), 0.9);
    }

    @Test
    void aTitleMatchingMoreOfTheQueryRanksHigher() {
        List<Track> ranked = ranker.rank(q("mellow rain"),
                List.of(t("a", "Sunshine"), t("b", "Mellow Rain"), t("c", "Rain Song")));
        assertEquals(List.of("b", "c", "a"), ranked.stream().map(Track::id).toList());
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
