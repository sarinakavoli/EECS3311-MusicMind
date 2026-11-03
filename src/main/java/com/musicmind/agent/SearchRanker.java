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

    /**
     * Puts what the user typed first. Order: a title equal to the typed text, a title holding every typed word,
     * a title plus artist names holding every typed word, then the results from the AI's reading. Typed results
     * that match none of those are kept (after the rest) only when the AI's reading found nothing. Tracks with
     * the same title, artists and duration in seconds count once; the first stays.
     */
    public List<Track> merge(String typed, List<Track> typedResults, List<Track> meaningResults) {
        String typedText = normalise(typed);
        Set<String> typedWords = words(typed);
        if (typedWords.isEmpty()) {
            return dedupe(meaningResults);
        }
        Set<String> meaningIds = new HashSet<>();
        meaningResults.forEach(t -> meaningIds.add(t.id()));
        List<Track> pool = new ArrayList<>(typedResults);
        pool.addAll(meaningResults);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Track t = pool.get(i);
            int tier;
            if (normalise(t.title()).equals(typedText)) {
                tier = 0;
            } else if (words(t.title()).containsAll(typedWords)) {
                tier = 1;
            } else if (words(t.title() + " " + String.join(" ", t.artistNames())).containsAll(typedWords)) {
                tier = 2;
            } else if (meaningIds.contains(t.id())) {
                tier = 3;
            } else if (meaningResults.isEmpty()) {
                tier = 4;
            } else {
                continue;
            }
            scored.add(new Scored(t, tier, i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).thenComparingInt(Scored::index));
        return dedupe(scored.stream().map(Scored::track).toList());
    }

    private static List<Track> dedupe(List<Track> tracks) {
        Set<String> seen = new HashSet<>();
        List<Track> out = new ArrayList<>();
        for (Track t : tracks) {
            List<String> artists = t.artistNames().stream().map(SearchRanker::normalise).sorted().toList();
            if (seen.add(normalise(t.title()) + "|" + artists + "|" + t.durationSec())) {
                out.add(t);
            }
        }
        return out;
    }

    private static String normalise(String text) {
        return text == null ? "" : String.join(" ", wordList(text));
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
        return new HashSet<>(wordList(text));
    }

    private static List<String> wordList(String text) {
        List<String> out = new ArrayList<>();
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
