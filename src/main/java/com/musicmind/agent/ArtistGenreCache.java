package com.musicmind.agent;

import java.util.List;
import java.util.Optional;

/** Read-only view of the cached MusicBrainz genres of an artist, by Spotify artist id. Never makes a request. */
@FunctionalInterface
public interface ArtistGenreCache {

    /** A cache that knows nothing. */
    ArtistGenreCache NONE = artistId -> Optional.empty();

    /** The fresh cached genres, or empty on a miss, an expired entry or an unreachable cache. */
    Optional<List<String>> cached(String artistId);
}
