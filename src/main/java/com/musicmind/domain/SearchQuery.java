package com.musicmind.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A natural-language search turned into Spotify search terms (F3): free keywords,
 * an optional genre, field filters such as {@code year} or {@code artist}, and the
 * parser's confidence (0..1).
 */
public record SearchQuery(String keywords, String genre, Map<String, String> filters, double confidence) {
    public SearchQuery {
        filters = filters == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }

    /** The {@code q} value for Spotify's search endpoint. */
    public String toSpotifyQuery() {
        StringBuilder q = new StringBuilder();
        if (keywords != null && !keywords.isBlank()) {
            q.append(keywords.trim());
        }
        if (genre != null && !genre.isBlank()) {
            append(q, "genre", genre);
        }
        filters.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                append(q, key, value);
            }
        });
        return q.toString();
    }

    private static void append(StringBuilder q, String field, String value) {
        if (q.length() > 0) {
            q.append(' ');
        }
        String v = value.trim();
        q.append(field).append(':').append(v.contains(" ") ? "\"" + v + "\"" : v);
    }
}
