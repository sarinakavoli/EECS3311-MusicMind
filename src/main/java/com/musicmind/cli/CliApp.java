package com.musicmind.cli;

import com.musicmind.controller.AppController;
import com.musicmind.controller.TasteProfileResult;
import com.musicmind.domain.Artist;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
            {"profile",   "Build and show your music taste profile (F1) [short|medium|long]"},
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

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            printHelp();
            return;
        }
        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "profile" -> runProfile(rest);
            case "trends", "search", "discover", "create",
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

    /** F1 — View Taste Profile. */
    private static void runProfile(String[] rest) {
        String timeRange = rest.length > 0 ? rest[0] : "medium";
        AppController controller = AppController.create();

        if (!controller.isSpotifyConfigured()) {
            System.out.println("Spotify is not configured yet.");
            System.out.println("  Copy .env.example to .env and set SPOTIFY_CLIENT_ID (see docs/STAGE2_PLAN.md).");
            return;
        }
        System.out.println("[musicmind] Building your taste profile over the '" + timeRange + "' term…");
        System.out.println("[musicmind] The first run opens your browser to connect Spotify.");
        try {
            TasteProfileResult result = controller.viewTasteProfile(timeRange);
            printProfile(result, controller.isLlmConfigured());
        } catch (RuntimeException e) {
            System.out.println("Could not build your profile: " + e.getMessage());
        }
    }

    private static void printProfile(TasteProfileResult result, boolean llmConfigured) {
        System.out.println();
        System.out.println("===== Your MusicMind Taste Profile =====");
        System.out.println("Spotify user: " + result.userId());
        System.out.println();

        System.out.println("Top genres:");
        List<Map.Entry<String, Double>> genres = result.rankedGenres(8);
        if (genres.isEmpty()) {
            System.out.println("  (no genre data — not enough listening history yet)");
        } else {
            for (Map.Entry<String, Double> g : genres) {
                System.out.printf("  %-24s %5.1f%%%n", g.getKey(), g.getValue() * 100);
            }
        }
        System.out.println();

        String artists = result.profile().topArtists().stream()
                .limit(5).map(Artist::name).collect(Collectors.joining(", "));
        System.out.println("Top artists: " + (artists.isEmpty() ? "(none)" : artists));
        System.out.println();

        System.out.println("Summary" + (llmConfigured ? " (by the LLM):" : " (offline fallback — set LLM_API_KEY for an AI summary):"));
        System.out.println("  " + result.summary());
        System.out.println();
        System.out.println("(Saved to memory — re-running shows it was remembered.)");
    }

    private static void printHelp() {
        System.out.println("MusicMind CLI — available commands:");
        for (String[] c : COMMANDS) {
            System.out.printf("  %-10s %s%n", c[0], c[1]);
        }
    }
}
