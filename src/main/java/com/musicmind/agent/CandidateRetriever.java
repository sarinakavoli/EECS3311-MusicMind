package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.TagService;
import com.musicmind.tool.ToolManager;
import com.musicmind.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final List<String> errors = new ArrayList<>();
    private int successes;
    // Spotify lookups made for Last.fm songs in this run, against lastfm.maxTracks.
    private int tagLookups;
    // Set by the first failed Last.fm call: no further tag is tried this run, so a down Last.fm costs one timeout.
    private boolean tagServiceFailed;

    CandidateRetriever(ToolManager tools, Config config) {
        this(tools, config, TagService.NONE);
    }

    CandidateRetriever(ToolManager tools, Config config, TagService tagService) {
        this(tools, config, tagService, new LinkedHashMap<>());
    }

    CandidateRetriever(ToolManager tools, Config config, TagService tagService, Map<String, Optional<Track>> matchCache) {
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
        Map<String, Track> byId = new LinkedHashMap<>();
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
        int used = 0;
        for (TagTrack wanted : listed) {
            if (used >= room || tagLookups >= config.lastfmMaxTracks()) {
                break;
            }
            used++;
            tagLookups++;
            Optional<Track> match = lookup(wanted);
            match.ifPresent(hit -> byId.putIfAbsent(hit.id(), hit));
        }
        return new ArrayList<>(byId.values());
    }

    /** The Spotify track for a Last.fm song: from the session cache, else one search; a failed search is not cached. */
    private Optional<Track> lookup(TagTrack wanted) {
        String key = SearchRanker.normalise(wanted.title()) + "|" + SearchRanker.normalise(wanted.artist());
        Optional<Track> cached = matchCache.get(key);
        if (cached != null) {
            return cached;
        }
        String query = "track:\"" + wanted.title().replace("\"", " ") + "\" artist:\""
                + wanted.artist().replace("\"", " ") + "\"";
        ToolResult result = tools.execute("search", Map.of("query", query, "limit", 1));
        if (!result.ok()) {
            errors.add(result.error());
            return Optional.empty();
        }
        successes++;
        @SuppressWarnings("unchecked")
        List<Track> hits = (List<Track>) result.payload();
        Optional<Track> match = hits.stream().filter(hit -> matches(wanted, hit)).findFirst();
        matchCache.put(key, match);
        return match;
    }

    private static boolean matches(TagTrack wanted, Track hit) {
        String title = SearchRanker.normalise(wanted.title());
        String artist = SearchRanker.normalise(wanted.artist());
        if (title.isEmpty() || artist.isEmpty() || hit.title() == null) {
            return false;
        }
        boolean titleOk = SearchRanker.normalise(hit.title()).equals(title)
                || SearchRanker.normalise(hit.title().replaceAll("\\([^)]*\\)|\\[[^]]*]| - .*$", "")).equals(title);
        return titleOk && hit.artistNames().stream().anyMatch(n -> SearchRanker.normalise(n).equals(artist));
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
