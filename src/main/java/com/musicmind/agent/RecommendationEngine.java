package com.musicmind.agent;

import com.musicmind.agent.strategy.BalancedStrategy;
import com.musicmind.agent.strategy.AbstractRecommendationStrategy;
import com.musicmind.agent.strategy.RecommendationStrategy;
import com.musicmind.agent.strategy.StrategySelector;
import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.Verdict;
import com.musicmind.music.MusicServiceException;
import com.musicmind.tool.ToolManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Context of the Strategy pattern and the project's own recommender (F4). Spotify's
 * recommendation endpoint is not available, so candidates come from the user's listening data:
 * their top, saved and recently played tracks (the familiar pool unless disabled by
 * {@code allowLibraryTracks == false}, in which case it holds new tracks by known artists), genre-adjacent searches and
 * the albums of their top artists (the novel pool). The engine filters blocked artists and
 * unwanted explicit tracks, lets the current {@link RecommendationStrategy} pick, then scores and
 * orders what was picked. Everything is deterministic and no LLM is involved.
 *
 * <p>Too few candidates for a niche taste widen the genre set and lower the novelty target in
 * configured steps; every step is reported in {@link #lastNotices()}. Why each track was chosen is
 * in its {@link Recommendation#reason()} and in {@link #lastTrace()}. Nothing is persisted.
 *
 * <p>{@link #score} and the notices describe the most recent {@link #recommend} call, so one
 * engine serves one run at a time.
 */
public class RecommendationEngine {

    private static final String FAMILIAR_POOL = "familiar";
    private static final String NOVEL_POOL = "novel";

    enum Source { LIBRARY, GENRE, ALBUM }

    record Origin(Source source, String genre) { }

    private final ToolManager tools;
    private final Config config;
    private final StrategySelector selector;
    private RecommendationStrategy strategy;
    // Per-run: whether the current run may pick tracks the user already has.
    private boolean allowLibraryTracks = true;
    // The strategy used by the current run: the configured one, or a re-chosen one after widening.
    private RecommendationStrategy runStrategy;

    // State of the most recent run, read by score() and the last* accessors.
    private ListeningProfile profile;
    private Map<String, Origin> origins = Map.of();
    // Genres the user asked for in this run (a mood, preferred styles); searched first and always a good match.
    private Set<String> seedGenres = Set.of();
    // The ones of those genres that were actually searched, for the trace.
    private List<String> searchedSeeds = List.of();
    private Map<String, Double> genreAffinity = Map.of();
    private Map<String, Double> artistAffinity = Map.of();
    private Map<String, Verdict> verdicts = Map.of();
    private DecisionTrace trace;
    private List<String> notices = List.of();

    public RecommendationEngine(ToolManager tools, Config config) {
        this.tools = tools;
        this.config = config;
        this.selector = new StrategySelector(config);
        this.strategy = new BalancedStrategy(config);
    }

    public synchronized void setStrategy(RecommendationStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** Picks the strategy that fits a familiar share (1.0 = all familiar). */
    public synchronized void chooseStrategy(double familiarRatio) {
        this.strategy = selector.select(familiarRatio);
    }

    /** Why the last run chose what it did, or null before the first run. */
    public synchronized DecisionTrace lastTrace() {
        return trace;
    }

    /** What the last run wants the user told: widening, shortfalls, filtered-out candidates, failed reads. */
    public synchronized List<String> lastNotices() {
        return notices;
    }

    /**
     * Recommends tracks for the profile under the spec, adjusted by the user's feedback.
     * The spec's blocked artists and explicit setting are honoured as given; the caller merges
     * stored preferences into it first.
     *
     * @throws MusicServiceException if every Spotify read failed
     */
    public List<Recommendation> recommend(ListeningProfile profile, PlaylistSpec spec, List<Feedback> feedback) {
        return recommend(profile, spec, feedback, true);
    }

    /**
     * As {@link #recommend(ListeningProfile, PlaylistSpec, List)}, but the caller says per run whether
     * tracks the user already has (top, saved, recently played) may be picked. Discovery (F4) passes
     * false; playlist creation (F5) passes true. Nothing is remembered between runs.
     */
    public List<Recommendation> recommend(ListeningProfile profile, PlaylistSpec spec,
                                          List<Feedback> feedback, boolean allowLibraryTracks) {
        return recommend(profile, spec, feedback, allowLibraryTracks, List.of());
    }

    /**
     * As the four-argument form, but {@code seedGenres} (a mood's genres, the user's preferred styles) are
     * searched before the genres of the user's own taste and always count as a match, and the reasons say
     * the user asked for them.
     */
    public synchronized List<Recommendation> recommend(ListeningProfile profile, PlaylistSpec spec,
                                                       List<Feedback> feedback, boolean allowLibraryTracks,
                                                       List<String> seedGenres) {
        return recommend(retrieve(profile, spec, allowLibraryTracks, seedGenres), spec, feedback);
    }

    /**
     * Reads the candidates for a run: the user's own tracks, the albums of their top artists (not the
     * spec's blocked ones) and the tracks found for the first genres. Playlist creation reads once, checks
     * that the request can be met, and then selects from the same {@link Retrieval}.
     */
    public synchronized Retrieval retrieve(ListeningProfile profile, PlaylistSpec spec,
                                           boolean allowLibraryTracks, List<String> seedGenres) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(spec, "spec");
        Map<String, Origin> found = new HashMap<>();
        CandidateRetriever retriever = new CandidateRetriever(tools, config);
        String range = profile.range() != null ? profile.range() : config.profileDefaultRange();
        List<Track> library = retriever.library(range);
        library.forEach(t -> found.put(t.id(), new Origin(Source.LIBRARY, null)));
        Set<String> libraryIds = new HashSet<>();
        library.forEach(t -> libraryIds.add(t.id()));
        if (!allowLibraryTracks) {
            // Also the profile's known tracks, in case a library read failed or a list was capped.
            libraryIds.addAll(profile.knownTrackIds());
        }

        List<String> blockedSeeds = spec.blockedArtists();
        List<String> artistSeeds = profile.topArtists().stream().map(Artist::id)
                .filter(id -> !blockedSeeds.contains(id)).limit(config.discoverArtistSeeds()).toList();
        List<Track> albumTracks = retriever.albumTracks(artistSeeds);
        albumTracks.forEach(t -> found.putIfAbsent(t.id(), new Origin(Source.ALBUM, null)));

        Set<String> seeds = new LinkedHashSet<>(seedGenres);
        Retrieval retrieval = new Retrieval(profile, allowLibraryTracks, seeds, retriever, library, libraryIds,
                albumTracks, rankedGenres(profile, seeds), new HashMap<>(), found);
        fetchGenres(retrieval, config.discoverInitialGenres());
        return retrieval;
    }

    /**
     * Recommends tracks from what {@link #retrieve} read, adjusted by the user's feedback, widening the
     * genre set when too few fit (reading only the extra genres).
     *
     * @throws MusicServiceException if every Spotify read failed
     */
    public synchronized List<Recommendation> recommend(Retrieval retrieval, PlaylistSpec spec,
                                                       List<Feedback> feedback) {
        Objects.requireNonNull(retrieval, "retrieval");
        Objects.requireNonNull(spec, "spec");
        this.allowLibraryTracks = retrieval.allowLibraryTracks;
        this.profile = retrieval.profile;
        this.runStrategy = strategy;
        this.verdicts = latestVerdicts(feedback);
        double max = profile.genreHistogram().values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        this.genreAffinity = genreAffinities(profile, max);
        this.artistAffinity = artistAffinities(profile, max);
        this.origins = retrieval.origins;
        this.seedGenres = retrieval.seedGenres;
        List<String> notes = new ArrayList<>();

        CandidateRetriever retriever = retrieval.retriever;
        List<String> genres = retrieval.genres;

        double ratio = spec.familiarRatio();
        int genreLimit = config.discoverInitialGenres();
        int steps = 0;
        double startRatio = ratio;
        Selection selection;
        while (true) {
            fetchGenres(retrieval, genreLimit);
            PlaylistSpec working = withRatio(spec, ratio);
            List<Track> novel = new ArrayList<>(retrieval.albumTracks);
            genres.subList(0, Math.min(genreLimit, genres.size()))
                    .forEach(g -> novel.addAll(retrieval.genreTracks.get(g)));
            selection = select(retrieval.library, retrieval.libraryIds, novel, working);
            // F5 (library allowed) only reads more genres; the familiar ratio and strategy are the user's.
            // Lowering the novelty target is F4's.
            boolean canWiden = steps < config.discoverWidenSteps()
                    && (genreLimit < genres.size() || (!allowLibraryTracks && ratio < 1.0));
            if (enough(selection, working) || !canWiden) {
                break;
            }
            steps++;
            genreLimit += config.discoverWidenGenresPerStep();
            if (!allowLibraryTracks) {
                ratio = Math.min(1.0, ratio + config.discoverWidenNoveltyStep());
                runStrategy = selector.select(ratio);
            }
        }

        if (retriever.successes() == 0 && !retriever.errors().isEmpty()) {
            throw new MusicServiceException(String.format(config.wording("discoverAllReadsFailed"), retriever.errors().size()));
        }
        this.searchedSeeds = retrieval.seedGenres.stream().filter(retrieval.genreTracks::containsKey).toList();
        if (steps > 0 && !allowLibraryTracks) {
            notes.add(String.format(config.wording("discoverWidened"), Math.min(genreLimit, genres.size()),
                    percent(1 - startRatio), percent(1 - ratio)));
        }
        List<Recommendation> result = selection.recommendations();
        if (result.isEmpty()) {
            notes.add(selection.removedBlocked() + selection.removedExplicit() > 0
                    ? String.format(config.wording("discoverAllFiltered"), filterSummary(selection))
                    : config.wording("discoverNoResults"));
        } else if (steps > 0 && !allowLibraryTracks && !enough(selection, withRatio(spec, ratio))) {
            notes.add(String.format(config.wording("discoverFewResults"), result.size()));
        }
        if (allowLibraryTracks && !result.isEmpty()) {
            int wantedNew = (int) Math.round(result.size() * (1 - spec.familiarRatio()));
            if (selection.novelSelected() < wantedNew) {
                notes.add(String.format(config.wording("createFewNewArtists"), selection.novelSelected(), wantedNew));
            }
            if (!seedGenres.isEmpty() && selection.seedMatched() < result.size()) {
                notes.add(String.format(config.wording("createFewGenreMatches"), selection.seedMatched(),
                        result.size(), String.join(", ", seedGenres)));
            }
        }
        if (!retriever.errors().isEmpty()) {
            notes.add(String.format(config.wording("discoverReadFailed"), retriever.errors().size()));
        }
        this.trace = buildTrace(result, spec, selection);
        this.notices = List.copyOf(notes);
        return result;
    }

    /** Reads the tracks of the first {@code limit} genres that have not been read yet. */
    private void fetchGenres(Retrieval retrieval, int limit) {
        for (String genre : retrieval.genres.subList(0, Math.min(limit, retrieval.genres.size()))) {
            retrieval.genreTracks.computeIfAbsent(genre, g -> {
                List<Track> found = retrieval.retriever.byGenre(g);
                found.forEach(t -> retrieval.origins.putIfAbsent(t.id(), new Origin(Source.GENRE, g)));
                return found;
            });
        }
    }

    /**
     * The score of a track under the spec, in the context of the last run: how well its familiarity
     * matches the requested familiar share, how strongly its genres match the user's taste, plus a
     * boost if the user accepted it before or a penalty if they rejected it.
     */
    public synchronized double score(Track track, PlaylistSpec spec) {
        double familiarity = familiarity(track);
        double fit = 1.0 - Math.abs(familiarity - spec.familiarRatio());
        double score = config.discoverFitWeight() * fit + config.discoverGenreWeight() * affinity(track);
        if (isBareGenreTitle(track)) {
            score -= config.discoverGenreTitlePenalty();
        }
        Verdict verdict = verdicts.get(track.id());
        if (verdict == Verdict.ACCEPT) {
            score += config.discoverAcceptBoost();
        } else if (verdict == Verdict.REJECT) {
            score -= config.discoverRejectPenalty();
        }
        return score;
    }

    /**
     * True for a track found by searching a genre whose whole title is that genre ("Hip Hop" for hip hop):
     * letters and digits only, case ignored. Spotify's text search returns such titles first for a genre word.
     */
    private boolean isBareGenreTitle(Track track) {
        Origin origin = origins.get(track.id());
        return origin != null && origin.source() == Source.GENRE && origin.genre() != null
                && track.title() != null && !squash(origin.genre()).isEmpty()
                && squash(track.title()).equals(squash(origin.genre()));
    }

    private static String squash(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ---- selection ----

    private record Selection(List<Recommendation> recommendations, int novelSelected,
                             int removedBlocked, int removedExplicit, int seedMatched) {
    }

    /** Enough picks, and enough of them new, for the novelty the spec asked for. */
    private boolean enough(Selection selection, PlaylistSpec spec) {
        int needed = Math.min(config.discoverMinResults(), AbstractRecommendationStrategy.targetCount(spec, config));
        int neededNovel = (int) Math.round(needed * (1 - spec.familiarRatio()));
        return selection.recommendations().size() >= needed && selection.novelSelected() >= neededNovel;
    }

    private Selection select(List<Track> library, Set<String> libraryIds, List<Track> novelCandidates,
                             PlaylistSpec spec) {
        int blocked = 0;
        int explicit = 0;
        List<Track> familiar = new ArrayList<>();
        List<Track> novel = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Track> all = new ArrayList<>(library);
        all.addAll(novelCandidates);
        if (!allowLibraryTracks) {
            all.removeIf(t -> libraryIds.contains(t.id()));
        }
        for (Track t : all) {
            if (!seen.add(t.id())) {
                continue;
            }
            if (spec.blocks(t)) {
                blocked++;
            } else if (t.isExplicit() && !spec.allowExplicit()) {
                explicit++;
            } else {
                (isFamiliar(t, libraryIds) ? familiar : novel).add(t);
            }
        }
        // Tracks that match the genres the user asked for (a mood) rank first inside each pool, then by score.
        Comparator<Track> best = Comparator.<Track>comparingInt(t -> matchesSeed(t) ? 0 : 1)
                .thenComparing(Comparator.<Track>comparingDouble(t -> score(t, spec)).reversed());
        familiar.sort(best);
        novel.sort(best);

        runStrategy.bindProfile(profile);
        Map<String, List<Track>> pools = new LinkedHashMap<>();
        pools.put(FAMILIAR_POOL, familiar);
        pools.put(NOVEL_POOL, novel);
        Map<String, Track> picked = new LinkedHashMap<>();
        runStrategy.selectCandidates(pools, spec).forEach(t -> picked.putIfAbsent(t.id(), t));

        List<Recommendation> recs = new ArrayList<>();
        int novelSelected = 0;
        int seedMatched = 0;
        for (Track t : picked.values()) {
            if (matchesSeed(t)) {
                seedMatched++;
            }
            if (!isFamiliar(t, libraryIds)) {
                novelSelected++;
            }
            recs.add(new Recommendation(t, reason(t), familiarity(t), score(t, spec)));
        }
        recs.sort(Comparator.comparingDouble(Recommendation::score).reversed());
        return new Selection(recs, novelSelected, blocked, explicit, seedMatched);
    }

    /**
     * Familiar tracks are the user's own and any track by an artist the user already listens to; the
     * rest are by artists new to the user. Ties in score keep source order (sorts are stable).
     */
    private boolean isFamiliar(Track track, Set<String> libraryIds) {
        return libraryIds.contains(track.id()) || familiarity(track) >= 1.0;
    }

    /** True if the track was found for one of the requested genres or is by a known artist whose leading genre is one. */
    private boolean matchesSeed(Track track) {
        if (seedGenres.isEmpty()) {
            return false;
        }
        Origin origin = origins.get(track.id());
        if (origin != null && origin.genre() != null && seedGenres.contains(origin.genre())) {
            return true;
        }
        // Only an artist's leading genre (first in its ordered list) counts: any tag would overstate the match.
        return profile.topArtists().stream().filter(a -> track.artistIds().contains(a.id()))
                .anyMatch(a -> !a.genres().isEmpty() && seedGenres.contains(a.genres().get(0)));
    }

    // ---- scoring inputs ----

    private double familiarity(Track track) {
        Origin origin = origins.get(track.id());
        if (origin != null && origin.source() == Source.LIBRARY) {
            return 1.0;
        }
        return profile == null ? 0.0 : profile.familiarity(track);
    }

    /** Best genre affinity (0..1) of the track: its search genre and the genres of its top artists. */
    private double affinity(Track track) {
        Origin origin = origins.get(track.id());
        double best = origin != null && origin.genre() != null ? genreAffinity.getOrDefault(origin.genre(), 0.0) : 0.0;
        if (origin != null && origin.genre() != null && seedGenres.contains(origin.genre())) {
            best = 1.0; // the user asked for this genre
        }
        for (String artistId : track.artistIds()) {
            best = Math.max(best, artistAffinity.getOrDefault(artistId, 0.0));
        }
        return best;
    }

    /** Each genre's weight scaled so the user's top genre is 1.0. */
    private static Map<String, Double> genreAffinities(ListeningProfile profile, double max) {
        Map<String, Double> out = new HashMap<>();
        if (max > 0.0) {
            profile.genreHistogram().forEach((genre, weight) -> out.put(genre, weight / max));
        }
        return out;
    }

    /** For each top artist, the affinity of its best genre. */
    private static Map<String, Double> artistAffinities(ListeningProfile profile, double max) {
        Map<String, Double> out = new HashMap<>();
        if (max > 0.0) {
            for (Artist artist : profile.topArtists()) {
                out.put(artist.id(), artist.genres().stream()
                        .mapToDouble(g -> profile.genreHistogram().getOrDefault(g, 0.0) / max).max().orElse(0.0));
            }
        }
        return out;
    }

    /**
     * The genres the user asked for, then the profile's genres from most to least prominent
     * (equal weights fall back to alphabetical order).
     */
    private static List<String> rankedGenres(ListeningProfile profile, Set<String> seeds) {
        List<String> ranked = new ArrayList<>(seeds);
        profile.genreHistogram().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).filter(g -> !seeds.contains(g)).forEach(ranked::add);
        return ranked;
    }

    private static Map<String, Verdict> latestVerdicts(List<Feedback> feedback) {
        Map<String, Feedback> latest = new HashMap<>();
        for (Feedback f : feedback) {
            Feedback current = latest.get(f.trackId());
            if (current == null || !time(f).isBefore(time(current))) {
                latest.put(f.trackId(), f);
            }
        }
        Map<String, Verdict> out = new HashMap<>();
        latest.forEach((id, f) -> out.put(id, f.verdict()));
        return out;
    }

    private static Instant time(Feedback f) {
        return f.createdAt() == null ? Instant.MIN : f.createdAt();
    }

    // ---- wording ----

    private String reason(Track track) {
        List<String> parts = new ArrayList<>();
        Origin origin = origins.get(track.id());
        parts.add(familiarity(track) >= 1.0 ? config.wording("reasonKnownArtist") : config.wording("reasonNewArtist"));
        if (origin != null) {
            parts.add(switch (origin.source()) {
                case LIBRARY -> config.wording("reasonSourceLibrary");
                case GENRE -> String.format(config.wording(seedGenres.contains(origin.genre())
                        ? "reasonSourceSeedGenre" : "reasonSourceGenre"), origin.genre());
                case ALBUM -> config.wording("reasonSourceAlbum");
            });
        }
        Verdict verdict = verdicts.get(track.id());
        if (verdict == Verdict.ACCEPT) {
            parts.add(config.wording("reasonAccepted"));
        } else if (verdict == Verdict.REJECT) {
            parts.add(config.wording("reasonRejected"));
        }
        String sentence = String.join(", ", parts);
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1) + ".";
    }

    private DecisionTrace buildTrace(List<Recommendation> recs, PlaylistSpec spec, Selection selection) {
        Map<String, String> reasons = new LinkedHashMap<>();
        recs.forEach(r -> reasons.put(r.track().id(), r.reason()));
        List<String> satisfied = new ArrayList<>();
        if (!allowLibraryTracks) {
            satisfied.add(config.wording("constraintLibraryExcluded"));
        }
        if (!spec.blockedArtists().isEmpty() || !spec.blockedTracks().isEmpty()) {
            satisfied.add(config.wording("constraintBlockedExcluded"));
        }
        if (!spec.allowExplicit()) {
            satisfied.add(config.wording("constraintExplicitExcluded"));
        }
        if (!searchedSeeds.isEmpty()) {
            satisfied.add(String.format(config.wording("constraintSeedGenres"), String.join(", ", searchedSeeds)));
        }
        return new DecisionTrace(runStrategy.getClass().getSimpleName(), reasons, satisfied, List.copyOf(seedGenres));
    }

    private String filterSummary(Selection s) {
        List<String> parts = new ArrayList<>();
        if (s.removedBlocked() > 0) {
            parts.add(String.format(config.wording("discoverFilteredBlocked"), s.removedBlocked()));
        }
        if (s.removedExplicit() > 0) {
            parts.add(String.format(config.wording("discoverFilteredExplicit"), s.removedExplicit()));
        }
        return String.join(", ", parts);
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    private static PlaylistSpec withRatio(PlaylistSpec spec, double ratio) {
        return spec.withFamiliarRatio(ratio);
    }
}
