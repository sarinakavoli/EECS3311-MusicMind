package com.musicmind.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoodGenreMapTest {

    @Test
    void bundledMapCoversCommonMoodsWithGenres() {
        MoodGenreMap map = MoodGenreMap.load();
        for (String mood : List.of("chill", "energetic", "happy", "sad", "focus", "workout", "party", "romantic")) {
            assertFalse(map.genresFor(mood).isEmpty(), mood);
        }
    }

    @Test
    void lookupIgnoresCaseAndSurroundingSpace() {
        MoodGenreMap map = MoodGenreMap.load();
        assertEquals(map.genresFor("chill"), map.genresFor("  CHILL "));
    }

    @Test
    void unknownOrBlankMoodHasNoGenres() {
        MoodGenreMap map = MoodGenreMap.load();
        assertTrue(map.genresFor("nonsense-mood").isEmpty());
        assertTrue(map.genresFor(null).isEmpty());
        assertTrue(map.genresFor(" ").isEmpty());
    }

    @Test
    void readsAnyJsonObjectOfMoodsToGenreLists() {
        MoodGenreMap map = MoodGenreMap.fromJson("{\"Calm\": [\"ambient\", \"piano\"]}");
        assertEquals(List.of("ambient", "piano"), map.genresFor("calm"));
    }

    @Test
    void malformedJsonIsRejectedWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MoodGenreMap.fromJson("[1,2]"));
        assertTrue(e.getMessage().contains("mood-genres.json"), e.getMessage());
    }
}
