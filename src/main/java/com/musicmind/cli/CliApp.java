package com.musicmind.cli;

import com.musicmind.controller.AppController;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.music.AuthExpiredException;

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
            {"discover",  "Get personalised recommendations (F4)"},
            {"create",    "Create a playlist from a goal (F5)"},
            {"modify",    "Modify an existing playlist (F6)"},
            {"validate",  "Validate a playlist against its constraints (F7)"},
            {"prefs",     "View or set preferences (F8)"},
            {"feedback",  "Accept or reject a track (F9)"},
            {"explain",   "Explain why tracks were chosen (F10)"},
            {"history",   "Show agent history / restore a snapshot (F12)"},
            {"help",      "Show this help"},
    };

    /** How many genres and artists the text output lists. */
    private static final int GENRES_SHOWN = 8;
    private static final int ARTISTS_SHOWN = 5;
    private static final int TREND_GENRES_SHOWN = 3;

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
            case "discover", "create",
                 "modify", "validate", "prefs", "feedback", "explain", "history" ->
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

    private static void printHelp() {
        System.out.println("MusicMind CLI — available commands:");
        for (String[] c : COMMANDS) {
            System.out.printf("  %-10s %s%n", c[0], c[1]);
        }
    }
}
