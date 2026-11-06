package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.RateLimitException;
import com.musicmind.music.TagService;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the candidate pools for F4 through the {@link ToolManager}: the user's own tracks (top,
 * saved, recently played), tracks found by a genre search, and album tracks of given artists.
 * A tool that fails does not stop the others: its message is kept in {@link #errors()}.
 * Results are returned in the order Spotify gave them, de-duplicated by track id.
 */
class CandidateRetriever {

    private final ToolManager tools;
    private final Config config;
    private final TagService tagService;
    // Session cache of Last.fm song -> Spotify track (empty = confirmed no match); a failed search is never put in.
    private final Map<String, Optional<Track>> matchCache;
    // Session caches for Discover: Last.fm's similar list per seed, and Spotify's answer per similar artist; a failure is never put in.
    private final Map<String, List<String>> similarCache;
    private final Map<String, List<Track>> similarTracksCache;
    private final List<String> errors = new ArrayList<>();
    private int successes;
    // Spotify lookups made for Last.fm songs in this run, against lastfm.maxTracks.
    private int tagLookups;
    // Set by the first failed Last.fm call or a stopped Spotify match: no further tag is tried this run.
    private boolean tagServiceFailed;

    CandidateRetriever(ToolManager tools, Config config) {
        this(tools, config, TagService.NONE);
    }

    CandidateRetriever(ToolManager tools, Config config, TagService tagService) {
        this(tools, config, tagService, new LinkedHashMap<>());
    }

    CandidateRetriever(ToolManager tools, Config config, TagService tagService, Map<String, Optional<Track>> matchCache) {
        this(tools, config, tagService, matchCache, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    CandidateRetriever(ToolManager tools, Config config, TagService tagService, Map<String, Optional<Track>> matchCache,
                       Map<String, List<String>> similarCache, Map<String, List<Track>> similarTracksCache) {
        this.similarCache = similarCache;
        this.similarTracksCache = similarTracksCache;
        this.matchCache = matchCache;
        this.tools = tools;
        this.config = config;
        this.tagService = tagService;
    }

    /** Top tracks for the range, then saved tracks, then recently played. */
    List<Track> library(String range) {
        Map<String, Track> byId = new LinkedHashMap<>();
        // Spotify has no top items for a week; that range is built from recent plays and is read here as the default.
        String spotifyRange = "week".equals(range) ? config.profileDefaultRange() : range;
        addAll(byId, read("getTopItems", Map.of("type", "tracks", "range", spotifyRange)));
        addAll(byId, read("getSavedTracks", Map.of()));
        addAll(byId, read("getRecentlyPlayed", Map.of()));
        return new ArrayList<>(byId.values());
    }

    /** Tracks Spotify's search returns for the genre, sent as a plain keyword (R19). */
    List<Track> byGenre(String genre) {
        return searchTracks(new SearchQuery(null, genre, Map.of(), 1.0).toSpotifyQuery());
    }

    /** Tracks Spotify's search returns for a phrase such as "gym workout", searched as typed. */
    List<Track> byKeywords(String phrase) {
        return searchTracks(new SearchQuery(phrase, null, Map.of(), 1.0).toSpotifyQuery());
    }

    /**
     * The real Spotify tracks for the songs Last.fm lists under a tag. Each Last.fm song is looked up with one
     * Spotify search (limit 1, {@code track:"title" artist:"artist"}) and kept only when the normalised title
     * and one artist name both match; anything else is dropped, never replaced by a loose match. At most
     * {@code lastfm.maxTracks} lookups are made per run, across all tags. A failed Last.fm call or Spotify
     * search is recorded in {@link #errors()} and the rest carries on.
     */
    List<Track> byTag(String tag) {
        return byTag(tag, config.lastfmMaxTracks());
    }

    /**
     * As {@link #byTag(String)}, but this tag may use at most {@code share} lookups, so the run's tags split the
     * {@code lastfm.maxTracks} budget instead of the first one taking it all.
     */
    List<Track> byTag(String tag, int share) {
        int room = Math.min(share, config.lastfmMaxTracks() - tagLookups);
        if (room <= 0 || tagServiceFailed) {
            return List.of();
        }
        List<TagTrack> listed;
        try {
            listed = tagService.topTracks(tag, room);
        } catch (MusicServiceException e) {
            tagServiceFailed = true;
            errors.add(e.getMessage());
            return List.of();
        }
        int asked = Math.min(room, listed.size());
        tagLookups += asked;
        TagTrackMatcher.Match match = new TagTrackMatcher(tools, matchCache).match(listed, asked);
        errors.addAll(match.errors());
        successes += match.searched();
        if (match.stopped()) {
            tagServiceFailed = true; // a rate limit or expired login: no further tag is looked up this run
        }
        return match.tracks();
    }

    /**
     * Songs by artists Last.fm lists as similar to the user's top artists (Discover only). For each of the first
     * {@code lastfm.discoverSeedArtists} names, {@code lastfm.similarPerArtist} similar artists are read; each
     * similar artist that is not one of the user's own gets one Spotify search {@code artist:"name"} for
     * {@code lastfm.similarTracksPerArtist} tracks. A track is kept only when one of its artists has exactly
     * that name (normalised) and none of its artists is in {@code knownArtistIds}. At most {@code maxSearches}
     * searches are made; the first rate limit or expired login ends the run, and a failed Last.fm call stops
     * asking Last.fm. Failures are recorded in {@link #errors()}.
     */
    List<Track> bySimilar(List<String> topArtistNames, Set<String> knownArtistIds, int maxSearches) {
        return bySimilar(topArtistNames, topArtistNames, knownArtistIds, maxSearches);
    }

    /**
     * As above, but {@code seedNames} (the top artists to ask Last.fm about, e.g. without blocked ones) can be fewer
     * than {@code ownNames} (every top artist, none of whom is searched).
     */
    List<Track> bySimilar(List<String> seedNames, List<String> ownNames, Set<String> knownArtistIds, int maxSearches) {
        List<String> topArtistNames = seedNames;
        Set<String> own = new HashSet<>();
        ownNames.forEach(n -> own.add(SearchRanker.normalise(n)));
        Map<String, Track> byId = new LinkedHashMap<>();
        Set<String> tried = new HashSet<>();
        int searches = 0;
        for (String seed : topArtistNames.stream().limit(config.lastfmDiscoverSeedArtists()).toList()) {
            List<String> similar = similarCache.get(SearchRanker.normalise(seed));
            if (similar == null) {
                try {
                    similar = tagService.similarArtists(seed, config.lastfmSimilarPerArtist());
                } catch (MusicServiceException e) {
                    errors.add(e.getMessage());
                    break;
                }
                similarCache.put(SearchRanker.normalise(seed), similar);
            }
            for (String name : similar) {
                String wanted = SearchRanker.normalise(name);
                if (searches >= maxSearches) {
                    return new ArrayList<>(byId.values());
                }
                if (wanted.isEmpty() || own.contains(wanted) || !tried.add(wanted)) {
                    continue;
                }
                searches++;
                List<Track> hits = similarTracksCache.get(wanted);
                if (hits == null) {
                    ToolResult result;
                    try {
                        result = tools.execute("search", Map.of("query", "artist:\"" + name.replace("\"", " ") + "\"",
                                "limit", config.lastfmSimilarTracksPerArtist()));
                    } catch (AuthExpiredException e) {
                        errors.add(e.getMessage());
                        return new ArrayList<>(byId.values());
                    }
                    if (!result.ok()) {
                        errors.add(result.error());
                        if (result.cause() instanceof RateLimitException || result.cause() instanceof AuthExpiredException) {
                            return new ArrayList<>(byId.values());
                        }
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<Track> answer = (List<Track>) result.payload();
                    hits = List.copyOf(answer);
                    similarTracksCache.put(wanted, hits);
                }
                successes++;
                for (Track hit : hits) {
                    boolean exact = hit.artistNames().stream().anyMatch(n -> SearchRanker.normalise(n).equals(wanted));
                    boolean known = hit.artistIds().stream().anyMatch(knownArtistIds::contains);
                    if (exact && !known) {
                        byId.putIfAbsent(hit.id(), hit);
                    }
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** One search, {@code discover.searchLimit} tracks, de-duplicated by id. */
    private List<Track> searchTracks(String query) {
        Map<String, Track> byId = new LinkedHashMap<>();
        addAll(byId, read("search", Map.of("query", query, "limit", config.discoverSearchLimit())));
        return new ArrayList<>(byId.values());
    }

    /** Tracks of the first few albums of each artist. */
    List<Track> albumTracks(List<String> artistIds) {
        Map<String, Track> byId = new LinkedHashMap<>();
        for (String artistId : artistIds) {
            List<Album> albums = read("getArtistAlbums", Map.of("artistId", artistId));
            for (Album album : albums.stream().limit(config.discoverAlbumsPerArtist()).toList()) {
                addAll(byId, read("getAlbumTracks", Map.of("albumId", album.id())));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Messages of the reads that failed. */
    List<String> errors() {
        return errors;
    }

    /** How many reads succeeded (an empty answer counts). */
    int successes() {
        return successes;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> read(String tool, Map<String, Object> args) {
        ToolResult result = tools.execute(tool, args);
        if (!result.ok()) {
            errors.add(result.error());
            return List.of();
        }
        successes++;
        return (List<T>) result.payload();
    }

    private static void addAll(Map<String, Track> byId, List<Track> tracks) {
        tracks.forEach(t -> byId.putIfAbsent(t.id(), t));
    }
}
