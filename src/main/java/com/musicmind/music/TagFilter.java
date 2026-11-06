package com.musicmind.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Drops Last.fm's junk tags (rarely voted, listener chatter, the artist's own name, years and decades) from a
 * {@code toptags} list. One place for artist tags and song tags, so a junk tag never reaches the app.
 */
final class TagFilter {

    private static final Pattern YEAR_OR_DECADE = Pattern.compile("^\\d{2,4}s?$");

    private TagFilter() {
    }

    /**
     * The surviving tag names, lower-cased and stripped, in Last.fm's order, without repeats, at most {@code keep}.
     * A tag with a vote count below {@code lastfm.minTagCount} is dropped; a tag with no count is kept.
     *
     * @param elements   the {@code tag} array elements, each {@code {"name": ..., "count": ...}}
     * @param artistName the artist the tags are for; a tag equal to it is dropped
     */
    static List<String> clean(JsonArray elements, String artistName, int keep, Config config) {
        String artist = artistName == null ? "" : artistName.strip().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (JsonElement e : elements) {
            try {
                JsonObject tag = e.getAsJsonObject();
                String name = tag.get("name").getAsString().strip().toLowerCase(Locale.ROOT);
                int count = tag.has("count") ? tag.get("count").getAsInt() : Integer.MAX_VALUE;
                if (!name.isEmpty() && count >= config.lastfmMinTagCount()
                        && !config.lastfmTagBlocklist().contains(name)
                        && !name.equals(artist) && !YEAR_OR_DECADE.matcher(name).matches()
                        && !out.contains(name)) {
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
