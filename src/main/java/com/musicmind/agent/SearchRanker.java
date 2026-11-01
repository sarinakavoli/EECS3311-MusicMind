package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic ordering of search results (F3). A track scores by the share of the
 * query's words found in its title plus a bonus for how early Spotify returned it; the
 * weights are in {@code musicmind.properties}. Ties keep Spotify's order, so the same
 * input always gives the same output.
 */
public class SearchRanker {

    private final Config config;

    public SearchRanker(Config config) {
        this.config = config;
    }

    public List<Track> rank(SearchQuery query, List<Track> results) {
        Set<String> words = words(query.keywords());
        int n = results.size();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Track t = results.get(i);
            double order = n <= 1 ? 1.0 : 1.0 - (double) i / (n - 1);
            double score = config.searchTitleMatchWeight() * titleMatch(words, t.title())
                    + config.searchSpotifyOrderWeight() * order;
            scored.add(new Scored(t, score, i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return scored.stream().map(Scored::track).toList();
    }

    private static double titleMatch(Set<String> words, String title) {
        if (words.isEmpty() || title == null) {
            return 0.0;
        }
        Set<String> inTitle = words(title);
        long hits = words.stream().filter(inTitle::contains).count();
        return (double) hits / words.size();
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text != null) {
            for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (!w.isEmpty()) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    private record Scored(Track track, double score, int index) { }
}
