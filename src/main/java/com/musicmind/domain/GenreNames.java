package com.musicmind.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One spelling per genre, for comparing and de-duplicating only ("hip hop", "hip-hop" and "hiphop" are the same
 * genre; so are "r&amp;b" and "rnb"). Text shown to the user keeps the spelling it came with. The canonical form is
 * also the one Last.fm tags use, so it is what is sent to Last.fm.
 */
public final class GenreNames {

    private static final Map<String, String> CANONICAL = new LinkedHashMap<>();

    static {
        for (String v : List.of("hip hop", "hip-hop", "hiphop")) CANONICAL.put(v, "hip-hop");
        for (String v : List.of("r&b", "rnb", "r and b", "rhythm and blues")) CANONICAL.put(v, "rnb");
        for (String v : List.of("trip hop", "trip-hop", "triphop")) CANONICAL.put(v, "trip-hop");
    }

    private GenreNames() { }

    /** Lower case, trimmed, with known variants mapped to one form; null gives an empty string. */
    public static String canonical(String genre) {
        if (genre == null) {
            return "";
        }
        String key = genre.strip().toLowerCase(Locale.ROOT);
        return CANONICAL.getOrDefault(key, key);
    }

    /** Every spelling of the genre (including its own), lower case. */
    public static List<String> variants(String genre) {
        String canonical = canonical(genre);
        List<String> all = new ArrayList<>();
        all.add(genre == null ? "" : genre.strip().toLowerCase(Locale.ROOT));
        CANONICAL.forEach((variant, c) -> {
            if (c.equals(canonical) && !all.contains(variant)) all.add(variant);
        });
        if (!all.contains(canonical)) all.add(canonical);
        return all;
    }
}
