package com.musicmind.music;

import com.musicmind.domain.Artist;

import java.util.List;
import java.util.Optional;

/**
 * Adapter target for artist genre metadata, independent of any provider.
 * Spotify returns no genres to this app, so a second source supplies them
 * ({@link MusicBrainzAdapter}).
 */
public interface GenreService {

    /**
     * The genres of an artist, most to least prominent. Empty when the source has
     * none or the lookup failed; never throws.
     */
    default List<String> genresFor(Artist artist) {
        return lookup(artist).orElse(List.of());
    }

    /**
     * Like {@link #genresFor} but tells a definite answer from a failure:
     * a present (possibly empty) list is authoritative, so it may be cached;
     * an empty Optional means the lookup could not be completed and should be retried later.
     */
    Optional<List<String>> lookup(Artist artist);
}
