package com.musicmind.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import com.musicmind.domain.GenreNames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Drops Last.fm's junk tags (rarely voted, listener chatter, the artist's own name, years and decades) from a
 * {@code toptags} list. One place for artist tags and song tags, so a junk tag never reaches the app.
 */
final class TagFilter {

    private static final Pattern YEAR_OR_DECADE = Pattern.compile("^\\d{2,4}s?$");

    private static final int MAX_NAME_LENGTH = 40;

    private TagFilter() {
    }

    /**
     * The surviving tag names, lower-cased and stripped, in Last.fm's order, without repeats (spellings of one genre count as a repeat; the first stays), at most {@code keep}.
     * Control characters, commas and whitespace runs become one space; names over 40 characters are dropped.
     * A tag with a weight below {@code lastfm.minTagCount} is dropped; a tag with no count is kept.
     *
     * @param elements   the {@code tag} array elements, each {@code {"name": ..., "count": ...}}
     * @param artistName the artist the tags are for; a tag equal to it is dropped
     */
    static List<String> clean(JsonArray elements, String artistName, int keep, Config config) {
        String artist = artistName == null ? "" : artistName.strip().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement e : elements) {
            try {
                JsonObject tag = e.getAsJsonObject();
                String name = tag.get("name").getAsString().replace(',', ' ').replaceAll("[\\p{Cntrl}\\s]+", " ")
                        .strip().toLowerCase(Locale.ROOT);
                int count = tag.has("count") ? tag.get("count").getAsInt() : Integer.MAX_VALUE;
                if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH && count >= config.lastfmMinTagCount()
                        && !config.lastfmTagBlocklist().contains(name)
                        && !name.equals(artist) && !YEAR_OR_DECADE.matcher(name).matches()
                        && seen.add(GenreNames.canonical(name))) {
                    out.add(name);
                }
            } catch (RuntimeException ignored) {
                // a malformed element is skipped
            }
            if (out.size() >= keep) {
                break;
            }
        }
        return out;
    }
}
