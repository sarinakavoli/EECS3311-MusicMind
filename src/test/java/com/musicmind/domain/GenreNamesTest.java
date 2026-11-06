package com.musicmind.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreNamesTest {

    @Test
    void variantsShareOneCanonicalForm() {
        for (String v : new String[] {"hip hop", "Hip-Hop", "hiphop"}) assertEquals("hip-hop", GenreNames.canonical(v));
        for (String v : new String[] {"r&b", "RnB", " r and b ", "rhythm and blues"}) assertEquals("rnb", GenreNames.canonical(v));
        for (String v : new String[] {"trip hop", "trip-hop"}) assertEquals("trip-hop", GenreNames.canonical(v));
        assertEquals("jazz", GenreNames.canonical(" Jazz "));
        assertEquals("", GenreNames.canonical(null));
    }

    @Test
    void variantsListEverySpelling() {
        assertTrue(GenreNames.variants("r&b").containsAll(java.util.List.of("r&b", "rnb")));
    }
}
