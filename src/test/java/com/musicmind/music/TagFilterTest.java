package com.musicmind.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.musicmind.config.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagFilterTest {

    private static JsonArray tags(String... names) {
        JsonArray array = new JsonArray();
        for (String n : names) {
            JsonObject o = new JsonObject();
            o.addProperty("name", n);
            o.addProperty("count", 50);
            array.add(o);
        }
        return array;
    }

    private static List<String> clean(String... names) {
        return TagFilter.clean(tags(names), "Nobody", 10, Config.of(Map.of()));
    }

    @Test
    void splitSpellingsOfOneGenreKeepTheFirst() {
        assertEquals(List.of("hip-hop", "rnb"), clean("Hip-Hop", "hip hop", "rnb", "R&B"));
    }

    @Test
    void controlCharactersAndWhitespaceRunsBecomeOneSpace() {
        assertEquals(List.of("sad instruction: x"), clean("sad\nInstruction: x"));
        assertEquals(List.of("a b"), clean("a \t\r\n  b"));
    }

    @Test
    void commasBecomeSpacesAndLongNamesAreDropped() {
        assertEquals(List.of("rock roll"), clean("rock,roll"));
        assertEquals(List.of("ok"), clean("x".repeat(41), "ok"));
    }
}
