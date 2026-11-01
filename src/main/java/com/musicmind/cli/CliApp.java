package com.musicmind.cli;

import com.musicmind.agent.PreferenceSettings;
import com.musicmind.config.Config;
import com.musicmind.controller.AppController;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.Verdict;
import com.musicmind.music.AuthExpiredException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command-line interface entry point. Every major feature is reachable as a
 * command; the CLI calls the same {@link AppController} as the GUI, so no
 * business logic is duplicated.
 *
 * Usage:  ./gradlew runCli --args="&lt;command&gt; [options]"
 */
public class CliApp {

    private static final String[][] COMMANDS = {
            {"profile",   "Build and show your music taste profile (F1) [--refresh] [--range short|medium|long]"},
            {"trends",    "Show how your taste changed over time (F2)"},
            {"search",    "Search for music by a natural-language query (F3)"},
            {"discover",  "Get personalised recommendations (F4) [--novelty 0.3]"},
            {"create",    "Create a playlist from a goal (F5)"},
            {"modify",    "Modify an existing playlist (F6)"},
            {"validate",  "Validate a playlist against its constraints (F7)"},
            {"prefs",     "View or set preferences (F8): prefs list | get <key> | set <key> <value>"},
            {"feedback",  "Accept or reject a track (F9): feedback <trackId> accept|reject"},
            {"explain",   "Explain why tracks were chosen (F10)"},
            {"history",   "Show agent history / restore a snapshot (F12)"},
            {"help",      "Show this help"},
    };

    /** How many genres and artists the text output lists. */
    private static final int GENRES_SHOWN = 8;
    private static final int ARTISTS_SHOWN = 5;
    private static final int TREND_GENRES_SHOWN = 3;
    private static final BufferedReader STDIN = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            printHelp();
            return;
        }
        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "profile" -> runProfile(rest);
            case "trends" -> runTrends();
            case "search" -> runSearch(rest);
            case "discover" -> runDiscover(rest);
            case "prefs" -> runPrefs(rest);
            case "feedback" -> runFeedback(rest);
            case "create" -> runCreate(rest);
            case "modify", "validate", "explain", "history" ->
                    System.out.println("[musicmind] '" + command + "' "
                            + (rest.length == 0 ? "" : String.join(" ", rest))
                            + " — command recognised; implemented in a later phase.");
            default -> {
                System.out.println("Unknown command: " + command);
                printHelp();
            }
        }
    }

    /** F1 — View Taste Profile: {@code profile [--refresh] [--range short|medium|long]}. */
    private static void runProfile(String[] rest) {
        boolean refresh = false;
        String range = null;
        for (int i = 0; i < rest.length; i++) {
            switch (rest[i]) {
                case "--refresh" -> refresh = true;
                case "--range" -> {
                    if (i + 1 >= rest.length) {
                        System.out.println("--range needs a value: short, medium or long.");
                        return;
                    }
                    range = rest[++i];
                }
                default -> {
                    System.out.println("Unknown option for profile: " + rest[i]);
                    return;
                }
            }
        }
        AppController controller = AppController.create();
        User user = signIn(controller);
        if (user == null) {
            return;
        }
        try {
            // R5: show the stored profile unless asked to rebuild; picking a range builds that range.
            Optional<ListeningProfile> stored = refresh || range != null
                    ? Optional.empty() : controller.lastProfile(user);
            if (stored.isPresent()) {
                System.out.println("[musicmind] Showing your stored profile. Use --refresh to rebuild it.");
                printProfile(stored.get(), controller);
            } else {
                System.out.println("[musicmind] Building your taste profile...");
                printProfile(range == null ? controller.analyzeTaste(user) : controller.analyzeTaste(user, range),
                        controller);
            }
            printMemoryWarning(controller);
        } catch (RuntimeException e) {
            reportFailure("build your profile", e);
        }
    }

    /** F2 — taste change across the short, medium and long ranges. */
    private static void runTrends() {
        AppController controller = AppController.create();
        User user = signIn(controller);
        if (user == null) {
            return;
        }
        System.out.println("[musicmind] Comparing your listening across time ranges...");
        try {
            printTrends(controller.analyzeTrends(user), controller);
        } catch (RuntimeException e) {
            reportFailure("compare your listening", e);
        }
    }

    /** F3 — natural-language search: {@code search "<query>"}. */
    private static void runSearch(String[] rest) {
        String query = String.join(" ", rest).strip();
        if (query.isEmpty()) {
            System.out.println("Usage: search \"<what you are looking for>\"");
            return;
        }
        AppController controller = AppController.create();
        User user = signIn(controller);
        if (user == null) {
            return;
        }
        try {
            List<Track> found = controller.searchMusic(query);
            System.out.println();
            for (int i = 0; i < found.size(); i++) {
                System.out.printf("%2d. %s  (%s)%n", i + 1, found.get(i).title(), found.get(i).uri());
            }
            if (controller.lastSearchLabel() != null) {
                System.out.println(controller.lastSearchLabel());
            }
            if (controller.lastSearchNotice() != null) {
                System.out.println(controller.lastSearchNotice());
            }
            System.out.println();
            System.out.println(controller.spotifyAttribution());
        } catch (RuntimeException e) {
            reportFailure("search", e);
        }
    }

    /** F4 — personalised discovery: {@code discover [--novelty 0.3]}. */
    private static void runDiscover(String[] rest) {
        Double novelty = null;
        for (int i = 0; i < rest.length; i++) {
            if (!rest[i].equals("--novelty")) {
                System.out.println("Unknown option for discover: " + rest[i]);
                return;
            }
            if (i + 1 >= rest.length) {
                System.out.println("--novelty needs a number from 0 (all familiar) to 1 (all new).");
                return;
            }
            try {
                novelty = Double.parseDouble(rest[++i]);
            } catch (NumberFormatException e) {
                System.out.println("--novelty needs a number from 0 (all familiar) to 1 (all new), got '" + rest[i] + "'.");
                return;
            }
        }
        AppController controller = AppController.create();
        if (signIn(controller) == null) {
            return;
        }
        try {
            System.out.println("[musicmind] Finding music for you...");
            List<Recommendation> recs = controller.discover(controller.discoverSpec(novelty));
            System.out.println();
            for (int i = 0; i < recs.size(); i++) {
                Recommendation r = recs.get(i);
                System.out.printf("%2d. %s  (%s)  score %.2f%n    %s%n", i + 1, r.track().title(),
                        r.track().id(), r.score(), r.reason());
            }
            controller.lastDiscoverNotices().forEach(n -> System.out.println("Notice: " + n));
            if (!recs.isEmpty()) {
                System.out.println();
                System.out.println("Give feedback with: feedback <trackId> accept|reject");
                System.out.println(controller.spotifyAttribution());
            }
        } catch (RuntimeException e) {
            reportFailure("find recommendations", e);
        }
    }

    /**
     * F5 and F11 — {@code create "<goal>" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name "..."]}.
     * A request that cannot be met as stated is put to the user as a numbered prompt.
     */
    private static void runCreate(String[] rest) {
        String goal = null;
        int minutes = 0;
        boolean noExplicit = false;
        double familiar = Double.NaN;
        String name = null;
        try {
            for (int i = 0; i < rest.length; i++) {
                switch (rest[i]) {
                    case "--minutes" -> minutes = Integer.parseInt(valueOf(rest, ++i, "--minutes"));
                    case "--no-explicit" -> noExplicit = true;
                    case "--familiar" -> familiar = Double.parseDouble(valueOf(rest, ++i, "--familiar"));
                    case "--name" -> name = valueOf(rest, ++i, "--name");
                    default -> {
                        if (rest[i].startsWith("--") || goal != null) {
                            System.out.println("Unknown option for create: " + rest[i]);
                            return;
                        }
                        goal = rest[i];
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Usage: create \"<goal>\" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name \"...\"]");
            System.out.println(e.getMessage());
            return;
        }
        if (goal == null) {
            System.out.println("Usage: create \"<goal>\" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name \"...\"]");
            return;
        }
        AppController controller = AppController.create();
        if (signIn(controller) == null) {
            return;
        }
        // Flags that were not given stay unset (0 minutes, NaN, null) and leave the goal's own values alone.
        PlaylistSpec flags = new PlaylistSpec(minutes, !noExplicit, familiar, List.of(), name);
        controller.setProgressListener(state -> System.out.println("[musicmind] " + state + "..."));
        controller.setNegotiationHandler(CliApp::askToRelax);
        try {
            Playlist playlist = controller.createPlaylist(new AgentRequest(goal, RequestKind.CREATE_PLAYLIST, flags));
            AgentSession session = controller.lastSession();
            System.out.println();
            System.out.println("Created \"" + playlist.name() + "\" (" + playlist.spotifyId() + ") with "
                    + playlist.tracks().size() + " tracks:");
            for (int i = 0; i < playlist.tracks().size(); i++) {
                Track t = playlist.tracks().get(i);
                System.out.printf("%2d. %s  (%s)%n", i + 1, t.title(), t.id());
            }
            session.notices().forEach(n -> System.out.println("Notice: " + n));
            System.out.println();
            System.out.println(session.explanation());
            System.out.println(controller.spotifyAttribution());
        } catch (RuntimeException e) {
            reportFailure("create the playlist", e);
        }
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value.");
        }
        return args[index];
    }

    /** F11 prompt: lists the conflicts and options; a number picks one, anything else cancels. */
    private static Optional<Relaxation> askToRelax(FeasibilityReport report, List<String> options) {
        System.out.println();
        report.conflicts().forEach(c -> System.out.println(Config.defaults().wording("cliProblemPrefix") + " " + c));
        for (int i = 0; i < options.size(); i++) {
            System.out.println("  " + (i + 1) + ") " + options.get(i));
        }
        System.out.print(String.format(Config.defaults().wording("cliChoosePrompt"), options.size()));
        System.out.flush();
        try {
            String line = STDIN.readLine();
            int choice = line == null ? -1 : Integer.parseInt(line.strip());
            return choice >= 1 && choice <= report.relaxations().size()
                    ? Optional.of(report.relaxations().get(choice - 1)) : Optional.empty();
        } catch (NumberFormatException | IOException e) {
            return Optional.empty();
        }
    }

    /** F8 — {@code prefs list}, {@code prefs get <key>}, {@code prefs set <key> <value>}. */
    private static void runPrefs(String[] rest) {
        String action = rest.length == 0 ? "" : rest[0].toLowerCase();
        boolean valid = switch (action) {
            case "list" -> rest.length == 1;
            case "get" -> rest.length == 2;
            case "set" -> rest.length >= 2;
            default -> false;
        };
        if (!valid) {
            System.out.println("Usage: prefs list | prefs get <key> | prefs set <key> <value>");
            System.out.println("Keys: blockedArtists (artist ids, comma separated), explicitPolicy (allow|block),");
            System.out.println("      familiarBias (0..1), preferredStyles (genres, comma separated)");
            return;
        }
        AppController controller = AppController.create();
        if (controller.isSpotifyConfigured() && signIn(controller) == null) {
            return;
        }
        try {
            switch (action) {
                case "set" -> {
                    UserPreference pref = controller.parsePreference(rest[1],
                            String.join(" ", Arrays.copyOfRange(rest, 2, rest.length)));
                    controller.setPreference(pref);
                    System.out.println(controller.memory().warning() == null
                            ? "Saved " + pref.key() + " = " + PreferenceSettings.display(pref)
                            : "(Not saved: " + controller.memory().warning() + ")");
                }
                case "get" -> {
                    List<UserPreference> found = controller.getPreferences().stream()
                            .filter(p -> p.key().equals(rest[1])).toList();
                    if (found.isEmpty()) {
                        System.out.println("No preference '" + rest[1] + "' is set.");
                    }
                    found.forEach(p -> System.out.println(p.key() + " = " + PreferenceSettings.display(p)));
                    printMemoryWarning(controller, "Showing defaults");
                }
                default -> {
                    List<UserPreference> all = controller.getPreferences();
                    if (all.isEmpty()) {
                        System.out.println("No preferences are set.");
                    }
                    all.forEach(p -> System.out.println(p.key() + " = " + PreferenceSettings.display(p)));
                    printMemoryWarning(controller, "Showing defaults");
                }
            }
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        } catch (RuntimeException e) {
            reportFailure("use preferences", e);
        }
    }

    /** F9 — {@code feedback <trackId> accept|reject}. */
    private static void runFeedback(String[] rest) {
        Verdict verdict = rest.length == 2 ? switch (rest[1].toLowerCase()) {
            case "accept" -> Verdict.ACCEPT;
            case "reject" -> Verdict.REJECT;
            default -> null;
        } : null;
        if (verdict == null) {
            System.out.println("Usage: feedback <trackId> accept|reject");
            return;
        }
        AppController controller = AppController.create();
        if (controller.isSpotifyConfigured() && signIn(controller) == null) {
            return;
        }
        try {
            controller.recordFeedback(rest[0], verdict);
            System.out.println(controller.memory().warning() == null
                    ? "Recorded: " + verdict.name().toLowerCase() + " for " + rest[0]
                    : "(Not saved: " + controller.memory().warning() + ")");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        } catch (RuntimeException e) {
            reportFailure("record your feedback", e);
        }
    }

    /** Logs in; prints why and returns null if that is not possible. */
    private static User signIn(AppController controller) {
        if (!controller.isSpotifyConfigured()) {
            System.out.println("Spotify is not configured yet.");
            System.out.println("  Copy .env.example to .env and set SPOTIFY_CLIENT_ID (see docs/STAGE2_PLAN.md).");
            return null;
        }
        System.out.println("[musicmind] The first run opens your browser to connect Spotify.");
        try {
            return controller.login();
        } catch (RuntimeException e) {
            reportFailure("log in to Spotify", e);
            return null;
        }
    }

    private static void reportFailure(String action, RuntimeException e) {
        if (e instanceof AuthExpiredException) {
            System.out.println("Your Spotify login has expired. Run the command again to log in.");
        } else {
            System.out.println("Could not " + action + ": " + e.getMessage());
        }
    }

    private static void printProfile(ListeningProfile profile, AppController controller) {
        System.out.println();
        System.out.println("===== Your MusicMind Taste Profile =====");
        if (profile.generatedAt() != null) {
            System.out.println("Generated: " + profile.generatedAt());
        }
        if (profile.range() != null) {
            System.out.println("Time range: " + profile.range());
        }
        profile.notices().forEach(n -> System.out.println("Notice: " + n));
        System.out.println();

        System.out.println("Top genres:");
        List<Map.Entry<String, Double>> genres = profile.rankedGenres(GENRES_SHOWN);
        if (genres.isEmpty()) {
            System.out.println("  (no genre data)");
        }
        for (Map.Entry<String, Double> g : genres) {
            System.out.printf("  %-24s %5.1f%%%n", g.getKey(), g.getValue() * 100);
        }
        System.out.println();

        String artists = profile.topArtists().stream().map(Artist::name).filter(n -> !n.isBlank())
                .limit(ARTISTS_SHOWN).collect(Collectors.joining(", "));
        System.out.println(artists.isEmpty()
                ? "Top artists counted: " + profile.topArtists().size() + " (names are not stored; use --refresh to see them)"
                : "Top artists: " + artists);
        System.out.println("Top tracks counted: " + profile.topTracks().size()
                + ", saved tracks counted: " + profile.savedTrackIds().size());
        System.out.println();
        System.out.println("Summary: " + profile.summary());
        System.out.println();
        System.out.println(controller.spotifyAttribution());
        System.out.println(controller.genreAttribution());
    }

    private static void printTrends(TrendReport report, AppController controller) {
        System.out.println();
        System.out.println("===== How Your Taste Has Changed =====");
        for (String range : controller.timeRanges()) {
            List<Artist> artists = report.artistsByRange().getOrDefault(range, List.of());
            String names = artists.stream().map(Artist::name).limit(ARTISTS_SHOWN).collect(Collectors.joining(", "));
            String genres = report.genresByRange().getOrDefault(range, Map.of()).entrySet().stream()
                    .limit(TREND_GENRES_SHOWN).map(Map.Entry::getKey).collect(Collectors.joining(", "));
            System.out.println(range + ":");
            System.out.println("  artists: " + (names.isEmpty() ? "(no data)" : names));
            System.out.println("  genres:  " + (genres.isEmpty() ? "(no data)" : genres));
        }
        System.out.println();
        printList("Genres added", report.addedGenres());
        printList("Genres dropped", report.droppedGenres());
        printList("Artists added", names(report, report.addedArtistIds()));
        printList("Artists dropped", names(report, report.droppedArtistIds()));
        report.gaps().forEach(g -> System.out.println("Notice: " + g));
        System.out.println();
        System.out.println(report.narrative());
        System.out.println();
        System.out.println(controller.spotifyAttribution());
        System.out.println(controller.genreAttribution());
    }

    private static void printList(String label, List<String> items) {
        System.out.println(label + ": " + (items.isEmpty() ? "(none)" : String.join(", ", items)));
    }

    private static List<String> names(TrendReport report, List<String> ids) {
        Map<String, String> byId = new LinkedHashMap<>();
        report.artistsByRange().values().forEach(list -> list.forEach(a -> byId.put(a.id(), a.name())));
        return ids.stream().map(id -> byId.getOrDefault(id, id)).toList();
    }

    private static void printMemoryWarning(AppController controller) {
        String warning = controller.memory().warning();
        if (warning != null) {
            System.out.println("(Not saved: " + warning + ")");
        }
    }

    private static void printMemoryWarning(AppController controller, String consequence) {
        String warning = controller.memory().warning();
        if (warning != null) {
            System.out.println("(" + consequence + ": " + warning + ")");
        }
    }

    private static void printHelp() {
        System.out.println("MusicMind CLI — available commands:");
        for (String[] c : COMMANDS) {
            System.out.printf("  %-10s %s%n", c[0], c[1]);
        }
    }
}
