package com.musicmind.agent;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link RecommendationEngine#retrieve} read from Spotify for one run: the user's own tracks,
 * the albums of their top artists and the tracks found for the first genres. It is handed back to
 * {@link RecommendationEngine#recommend(Retrieval, PlaylistSpec, List)} so
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
    /** Tracks found by searching the goal's activity phrases. */
    final List<Track> keywordTracks;
    /** Tracks the goal-fit review took out; genres read later are filtered by it too. */
    final Set<String> removedIds;
    /** The phrases searched for the goal's activity; a result titled with one of their words is usually stock music. */
    final List<String> searchTerms;
    /**
     * Ids of tracks a goal source vouched for (the goal's keyword search; Last.fm tags later). A library track
     * in this set fits the goal whatever its artist's genres are.
     */
    final Set<String> goalSourceIds;
    /** Real Spotify tracks matched to songs Last.fm lists under the goal's tags. */
    final List<Track> tagTracks;
    /** The tags the plan asked for; empty when the goal has none. */
    final List<String> tags;

    Retrieval(ListeningProfile profile, boolean allowLibraryTracks, Set<String> seedGenres,
              CandidateRetriever retriever, List<Track> library, Set<String> libraryIds,
              List<Track> albumTracks, List<String> genres, Map<String, List<Track>> genreTracks,
              Map<String, RecommendationEngine.Origin> origins) {
        this(profile, allowLibraryTracks, seedGenres, retriever, library, libraryIds, albumTracks, genres,
                genreTracks, origins, List.of(), Set.of(), List.of());
    }

    Retrieval(ListeningProfile profile, boolean allowLibraryTracks, Set<String> seedGenres,
              CandidateRetriever retriever, List<Track> library, Set<String> libraryIds,
              List<Track> albumTracks, List<String> genres, Map<String, List<Track>> genreTracks,
              Map<String, RecommendationEngine.Origin> origins, List<Track> keywordTracks, Set<String> removedIds,
              List<String> searchTerms) {
        this(profile, allowLibraryTracks, seedGenres, retriever, library, libraryIds, albumTracks, genres,
                genreTracks, origins, keywordTracks, removedIds, searchTerms, Set.of());
    }

    Retrieval(ListeningProfile profile, boolean allowLibraryTracks, Set<String> seedGenres,
              CandidateRetriever retriever, List<Track> library, Set<String> libraryIds,
              List<Track> albumTracks, List<String> genres, Map<String, List<Track>> genreTracks,
              Map<String, RecommendationEngine.Origin> origins, List<Track> keywordTracks, Set<String> removedIds,
              List<String> searchTerms, Set<String> goalSourceIds) {
        this(profile, allowLibraryTracks, seedGenres, retriever, library, libraryIds, albumTracks, genres,
                genreTracks, origins, keywordTracks, removedIds, searchTerms, goalSourceIds, List.of(), List.of());
    }

    Retrieval(ListeningProfile profile, boolean allowLibraryTracks, Set<String> seedGenres,
              CandidateRetriever retriever, List<Track> library, Set<String> libraryIds,
              List<Track> albumTracks, List<String> genres, Map<String, List<Track>> genreTracks,
              Map<String, RecommendationEngine.Origin> origins, List<Track> keywordTracks, Set<String> removedIds,
              List<String> searchTerms, Set<String> goalSourceIds, List<Track> tagTracks, List<String> tags) {
        this.tagTracks = tagTracks;
        this.tags = tags;
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
        this.keywordTracks = keywordTracks;
        this.removedIds = removedIds;
        this.searchTerms = searchTerms;
        this.goalSourceIds = goalSourceIds;
    }

    /**
     * This retrieval without the tracks whose ids are given (the goal-fit review's removals). The user's own
     * tracks, album tracks, genre and keyword tracks all lose them; the Spotify reads already made are shared,
     * so nothing is read again.
     */
    public Retrieval without(Set<String> trackIds) {
        Set<String> removed = new HashSet<>(removedIds);
        removed.addAll(trackIds);
        Map<String, List<Track>> byGenre = new LinkedHashMap<>();
        genreTracks.forEach((genre, tracks) -> byGenre.put(genre, keeping(tracks, removed)));
        return new Retrieval(profile, allowLibraryTracks, seedGenres, retriever, keeping(library, removed), libraryIds,
                keeping(albumTracks, removed), genres, byGenre, origins, keeping(keywordTracks, removed), Set.copyOf(removed),
                searchTerms, goalSourceIds, keeping(tagTracks, removed), tags);
    }

    /**
     * The genres already known for a track, with no request made: the genre it was searched under, the genres of
     * the user's own artists in the listening profile, and the cached MusicBrainz genres of its other artists.
     * Empty when none is known for any of its artists.
     */
    public List<String> knownGenres(Track track, ArtistGenreCache cache) {
        Set<String> found = new LinkedHashSet<>();
        RecommendationEngine.Origin origin = origins.get(track.id());
        if (origin != null && origin.genre() != null && !origin.genre().isBlank()) {
            found.add(origin.genre());
        }
        for (String artistId : track.artistIds()) {
            List<String> own = profile.topArtists().stream().filter(a -> a.id().equals(artistId))
                    .flatMap(a -> a.genres().stream()).toList();
            found.addAll(own.isEmpty() ? cache.cached(artistId).orElse(List.of()) : own);
        }
        return List.copyOf(found);
    }

    static List<Track> keeping(List<Track> tracks, Set<String> removed) {
        return tracks.stream().filter(t -> !removed.contains(t.id())).toList();
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
        keywordTracks.forEach(t -> all.putIfAbsent(t.id(), t));
        tagTracks.forEach(t -> all.putIfAbsent(t.id(), t));
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
