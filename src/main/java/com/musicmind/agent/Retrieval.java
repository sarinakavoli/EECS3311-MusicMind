package com.musicmind.agent;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link RecommendationEngine#retrieve} read from Spotify for one run: the user's own tracks,
 * the albums of their top artists and the tracks found for the first genres. It is handed back to
 * {@link RecommendationEngine#recommend(Retrieval, com.musicmind.domain.PlaylistSpec, List)} so
 * selecting, and selecting again after a relaxation or a failed validation, never reads Spotify twice.
 */
public final class Retrieval {

    final ListeningProfile profile;
    final boolean allowLibraryTracks;
    final Set<String> seedGenres;
    final CandidateRetriever retriever;
    final List<Track> library;
    final Set<String> libraryIds;
    final List<Track> albumTracks;
    final List<String> genres;
    final Map<String, List<Track>> genreTracks;
    final Map<String, RecommendationEngine.Origin> origins;

    Retrieval(ListeningProfile profile, boolean allowLibraryTracks, Set<String> seedGenres,
              CandidateRetriever retriever, List<Track> library, Set<String> libraryIds,
              List<Track> albumTracks, List<String> genres, Map<String, List<Track>> genreTracks,
              Map<String, RecommendationEngine.Origin> origins) {
        this.profile = profile;
        this.allowLibraryTracks = allowLibraryTracks;
        this.seedGenres = seedGenres;
        this.retriever = retriever;
        this.library = library;
        this.libraryIds = libraryIds;
        this.albumTracks = albumTracks;
        this.genres = genres;
        this.genreTracks = genreTracks;
        this.origins = origins;
    }

    /** True when nothing could be read at all (every Spotify read failed). */
    public boolean allReadsFailed() {
        return retriever.successes() == 0 && !retriever.errors().isEmpty();
    }

    /** How many Spotify reads failed while retrieving. */
    public int failedReads() {
        return retriever.errors().size();
    }

    /** The candidates read so far, split into tracks the user has and the rest. */
    public CandidatePool pool() {
        Map<String, Track> all = new LinkedHashMap<>();
        library.forEach(t -> all.putIfAbsent(t.id(), t));
        albumTracks.forEach(t -> all.putIfAbsent(t.id(), t));
        genreTracks.values().forEach(tracks -> tracks.forEach(t -> all.putIfAbsent(t.id(), t)));
        if (!allowLibraryTracks) {
            all.keySet().removeAll(libraryIds);
        }
        List<Track> familiar = all.values().stream().filter(this::isFamiliar).toList();
        List<Track> novel = all.values().stream().filter(t -> !isFamiliar(t)).toList();
        return new CandidatePool(familiar, novel);
    }

    /**
     * Familiar tracks are the user's own and any track by an artist the user already listens to;
     * novel tracks are by artists new to the user.
     */
    boolean isFamiliar(Track track) {
        RecommendationEngine.Origin origin = origins.get(track.id());
        return libraryIds.contains(track.id())
                || (origin != null && origin.source() == RecommendationEngine.Source.LIBRARY)
                || profile.familiarity(track) >= 1.0;
    }
}
