package com.musicmind.agent;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.llm.LLMProvider;

import java.util.stream.Collectors;

/**
 * Turns a {@link ListeningProfile} into a one-line, human-friendly summary using
 * the {@link LLMProvider} (F1). If the LLM is unavailable or returns something
 * unusable, it falls back to a deterministic sentence built from the profile, so
 * the feature always produces a sensible result.
 */
public class ProfileSummarizer {

    private final LLMProvider llm;

    public ProfileSummarizer(LLMProvider llm) {
        this.llm = llm;
    }

    public String summarize(ListeningProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return "Not enough listening history yet to describe your taste.";
        }
        String genres = topGenres(profile, 5);
        String artists = profile.topArtists().stream()
                .limit(5).map(Artist::name).collect(Collectors.joining(", "));

        String prompt = "In one friendly sentence (no preamble, no lists, no quotes), "
                + "summarise this music listener's taste. "
                + "Top genres: " + (genres.isEmpty() ? "unknown" : genres) + ". "
                + "Favourite artists: " + (artists.isEmpty() ? "various" : artists) + ".";

        try {
            String line = firstLine(llm.complete(prompt));
            if (!line.isBlank() && !line.startsWith("{")) {
                return line;
            }
        } catch (RuntimeException ignored) {
            // fall back to the deterministic summary below
        }
        return deterministic(profile, genres, artists);
    }

    private static String topGenres(ListeningProfile profile, int n) {
        return profile.genreHistogram().keySet().stream()
                .limit(n).collect(Collectors.joining(", "));
    }

    private static String deterministic(ListeningProfile profile, String genres, String artists) {
        String top = profile.topGenre();
        StringBuilder sb = new StringBuilder("Your taste leans ");
        sb.append(top != null ? "toward " + top : "across a mix of genres");
        if (!genres.isBlank()) sb.append(" (with ").append(genres).append(")");
        if (!artists.isBlank()) sb.append(", built around artists like ").append(artists);
        sb.append('.');
        return sb.toString();
    }

    /** First non-empty line, with any wrapping quotes stripped. */
    private static String firstLine(String text) {
        if (text == null) return "";
        for (String raw : text.strip().split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                if (line.length() > 1 && line.startsWith("\"") && line.endsWith("\"")) {
                    line = line.substring(1, line.length() - 1);
                }
                return line;
            }
        }
        return "";
    }
}
