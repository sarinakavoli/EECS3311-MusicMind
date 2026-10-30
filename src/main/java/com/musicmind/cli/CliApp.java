package com.musicmind.cli;

import java.util.Arrays;

/**
 * Command-line interface entry point. Every major feature is reachable as a
 * command; the CLI calls the same AppController / MusicAgentFacade as the GUI,
 * so no business logic is duplicated.
 *
 * Usage:  ./gradlew runCli --args="&lt;command&gt; [options]"
 */
public class CliApp {

    private static final String[][] COMMANDS = {
            {"profile",   "Build and show your music taste profile (F1)"},
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

        // Phase 1 will route these to AppController; the dispatch structure is here.
        switch (command) {
            case "profile", "trends", "search", "discover", "create",
                 "modify", "validate", "prefs", "feedback", "explain", "history" ->
                    System.out.println("[musicmind] '" + command + "' "
                            + (rest.length == 0 ? "" : String.join(" ", rest))
                            + " — command recognised; wiring to AppController in Phase 1.");
            default -> {
                System.out.println("Unknown command: " + command);
                printHelp();
            }
        }
    }

    private static void printHelp() {
        System.out.println("MusicMind CLI — available commands:");
        for (String[] c : COMMANDS) {
            System.out.printf("  %-10s %s%n", c[0], c[1]);
        }
    }
}
