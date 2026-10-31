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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Context of the Strategy pattern and the project's own recommender (F4). Spotify's
 * recommendation endpoint is not available, so candidates come from the user's listening data:
 * their top, saved and recently played tracks (the familiar pool), genre-adjacent searches and
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

    private enum Source { LIBRARY, GENRE, ALBUM }

    private record Origin(Source source, String genre) { }

    private final ToolManager tools;
    private final Config config;
    private final StrategySelector selector;
    private RecommendationStrategy strategy = new BalancedStrategy();

    // State of the most recent run, read by score() and the last* accessors.
    private ListeningProfile profile;
    private Map<String, Origin> origins = Map.of();
    private Map<String, Double> genreAffinity = Map.of();
    private Map<String, Double> artistAffinity = Map.of();
    private Map<String, Verdict> verdicts = Map.of();
    private DecisionTrace trace;
    private List<String> notices = List.of();

    public RecommendationEngine(ToolManager tools, Config config) {
        this.tools = tools;
        this.config = config;
        this.selector = new StrategySelector(config);
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
    public synchronized List<Recommendation> recommend(ListeningProfile profile, PlaylistSpec spec,
                                                       List<Feedback> feedback) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(spec, "spec");
        this.profile = profile;
        this.verdicts = latestVerdicts(feedback);
        double max = profile.genreHistogram().values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        this.genreAffinity = genreAffinities(profile, max);
        this.artistAffinity = artistAffinities(profile, max);
        this.origins = new HashMap<>();
        List<String> notes = new ArrayList<>();

        CandidateRetriever retriever = new CandidateRetriever(tools, config);
        String range = profile.range() != null ? profile.range() : config.profileDefaultRange();
        List<Track> library = retriever.library(range);
        library.forEach(t -> origins.put(t.id(), new Origin(Source.LIBRARY, null)));
        Set<String> libraryIds = new HashSet<>();
        library.forEach(t -> libraryIds.add(t.id()));

        List<String> blockedSeeds = spec.blockedArtists();
        List<String> artistSeeds = profile.topArtists().stream().map(Artist::id)
                .filter(id -> !blockedSeeds.contains(id)).limit(config.discoverArtistSeeds()).toList();
        List<Track> albumTracks = retriever.albumTracks(artistSeeds);
        albumTracks.forEach(t -> origins.putIfAbsent(t.id(), new Origin(Source.ALBUM, null)));

        List<String> genres = rankedGenres(profile);
        Map<String, List<Track>> genreTracks = new HashMap<>();

        double ratio = spec.familiarRatio();
        int genreLimit = config.discoverInitialGenres();
        int steps = 0;
        double startRatio = ratio;
        Selection selection;
        while (true) {
            for (String genre : genres.subList(0, Math.min(genreLimit, genres.size()))) {
                genreTracks.computeIfAbsent(genre, g -> {
                    List<Track> found = retriever.byGenre(g);
                    found.forEach(t -> origins.putIfAbsent(t.id(), new Origin(Source.GENRE, g)));
                    return found;
                });
            }
            PlaylistSpec working = withRatio(spec, ratio);
            List<Track> novel = new ArrayList<>(albumTracks);
            genres.subList(0, Math.min(genreLimit, genres.size()))
                    .forEach(g -> novel.addAll(genreTracks.get(g)));
            selection = select(library, libraryIds, novel, working);
            boolean canWiden = steps < config.discoverWidenSteps()
                    && (genreLimit < genres.size() || ratio < 1.0);
            if (enough(selection, working) || !canWiden) {
                break;
            }
            steps++;
            genreLimit += config.discoverWidenGenresPerStep();
            ratio = Math.min(1.0, ratio + config.discoverWidenNoveltyStep());
            chooseStrategy(ratio);
        }

        if (retriever.successes() == 0 && !retriever.errors().isEmpty()) {
            throw new MusicServiceException("Could not read your Spotify data. " + String.join(" ", retriever.errors()));
        }
        if (steps > 0) {
            notes.add(String.format(config.wording("discoverWidened"), Math.min(genreLimit, genres.size()),
                    percent(1 - startRatio), percent(1 - ratio)));
        }
        List<Recommendation> result = selection.recommendations();
        if (result.isEmpty()) {
            notes.add(selection.removedBlocked() + selection.removedExplicit() > 0
                    ? String.format(config.wording("discoverAllFiltered"), filterSummary(selection))
                    : config.wording("discoverNoResults"));
        } else if (steps > 0 && !enough(selection, withRatio(spec, ratio))) {
            notes.add(String.format(config.wording("discoverFewResults"), result.size()));
        }
        if (!retriever.errors().isEmpty()) {
            notes.add(String.format(config.wording("discoverReadFailed"), String.join(" ", retriever.errors())));
        }
        this.trace = buildTrace(result, spec, selection);
        this.notices = List.copyOf(notes);
        return result;
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
        Verdict verdict = verdicts.get(track.id());
        if (verdict == Verdict.ACCEPT) {
            score += config.discoverAcceptBoost();
        } else if (verdict == Verdict.REJECT) {
            score -= config.discoverRejectPenalty();
        }
        return score;
    }

    // ---- selection ----

    private record Selection(List<Recommendation> recommendations, int novelSelected,
                             int removedBlocked, int removedExplicit) {
    }

    /** Enough picks, and enough of them new, for the novelty the spec asked for. */
    private boolean enough(Selection selection, PlaylistSpec spec) {
        int needed = Math.min(config.discoverMinResults(), AbstractRecommendationStrategy.targetCount(spec));
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
        for (Track t : all) {
            if (!seen.add(t.id())) {
                continue;
            }
            if (t.artistIds().stream().anyMatch(spec::isBlocked)) {
                blocked++;
            } else if (t.isExplicit() && !spec.allowExplicit()) {
                explicit++;
            } else {
                (libraryIds.contains(t.id()) ? familiar : novel).add(t);
            }
        }
        Comparator<Track> best = Comparator.<Track>comparingDouble(t -> score(t, spec)).reversed()
                .thenComparing(Track::id);
        familiar.sort(best);
        novel.sort(best);

        strategy.bindProfile(profile);
        Map<String, List<Track>> pools = new LinkedHashMap<>();
        pools.put(FAMILIAR_POOL, familiar);
        pools.put(NOVEL_POOL, novel);
        Map<String, Track> picked = new LinkedHashMap<>();
        strategy.selectCandidates(pools, spec).forEach(t -> picked.putIfAbsent(t.id(), t));

        List<Recommendation> recs = new ArrayList<>();
        int novelSelected = 0;
        for (Track t : picked.values()) {
            if (!libraryIds.contains(t.id())) {
                novelSelected++;
            }
            recs.add(new Recommendation(t, reason(t), familiarity(t), score(t, spec)));
        }
        recs.sort(Comparator.comparingDouble(Recommendation::score).reversed()
                .thenComparing(r -> r.track().id()));
        return new Selection(recs, novelSelected, blocked, explicit);
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

    /** Genres from most to least prominent; equal weights fall back to alphabetical order. */
    private static List<String> rankedGenres(ListeningProfile profile) {
        return profile.genreHistogram().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
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
                case GENRE -> String.format(config.wording("reasonSourceGenre"), origin.genre());
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
        satisfied.add(config.wording("constraintLibraryExcluded"));
        if (!spec.blockedArtists().isEmpty()) {
            satisfied.add(config.wording("constraintBlockedExcluded"));
        }
        if (!spec.allowExplicit()) {
            satisfied.add(config.wording("constraintExplicitExcluded"));
        }
        return new DecisionTrace(strategy.getClass().getSimpleName(), reasons, satisfied);
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
        return new PlaylistSpec(spec.targetMinutes(), spec.allowExplicit(), ratio, spec.blockedArtists(), spec.name());
    }
}
