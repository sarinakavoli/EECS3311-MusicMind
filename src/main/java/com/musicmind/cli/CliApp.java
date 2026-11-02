package com.musicmind.cli;

import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.agent.state.SessionRecord;
import com.musicmind.config.Config;
import com.musicmind.controller.AppController;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.PlaylistSummary;
import com.musicmind.domain.RangeChange;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.ValidationResult;
import com.musicmind.domain.Verdict;
import com.musicmind.music.AuthExpiredException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Command-line interface. Every major feature is reachable as a command; the CLI calls the same
 * {@link AppController} as the GUI, so it holds no business logic: it parses arguments, calls the
 * controller and prints the result.
 *
 * <p>Run one command ({@code musicmind profile --refresh}) or none, which starts an interactive session
 * that ends with {@code exit}. {@link #run} returns the process status: {@value #OK} for success,
 * {@value #FAILED} when the command could not be completed and {@value #USAGE} when the arguments were wrong.
 * All wording is in {@code musicmind.properties}.
 *
 * <p>Usage: {@code ./gradlew runCli --args="profile"}, or {@code build/install/musicmind/bin/musicmind profile}.
 */
public class CliApp {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE = 2;

    /** The commands in the order help lists them. */
    static final List<String> COMMANDS = List.of("login", "help", "exit", "profile", "trends", "search", "candidates", "discover",
            "create", "playlists", "show", "modify", "undo", "validate", "prefs", "feedback", "explain", "history", "restore");

    /** How many genres and artists the text output lists. */
    private static final int GENRES_SHOWN = 8;
    private static final int ARTISTS_SHOWN = 5;
    private static final int TREND_GENRES_SHOWN = 3;

    private final Supplier<AppController> factory;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final Config config;
    private AppController controller;

    /**
     * @param factory builds the controller the first time a command needs one (help needs none)
     * @param in      where the interactive prompts (F11, restore) and the interactive session read from
     * @param out     normal output
     * @param err     failures and usage errors
     */
    public CliApp(Supplier<AppController> factory, BufferedReader in, PrintStream out, PrintStream err, Config config) {
        this.factory = factory;
        this.in = in;
        this.out = out;
        this.err = err;
        this.config = config;
    }

    public static void main(String[] args) {
        Config config = Config.load();
        CliApp app = new CliApp(AppController::create, new BufferedReader(new InputStreamReader(System.in)),
                System.out, System.err, config);
        System.exit(app.run(args));
    }

    /** Runs one command, or starts the interactive session when there is none. */
    public int run(String[] args) {
        if (args.length == 0) {
            return interactive();
        }
        return dispatch(args);
    }

    private int dispatch(String[] args) {
        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (command) {
            case "help", "--help", "-h" -> { printHelp(); return OK; }
            case "exit" -> { out.println(config.wording("cliGoodbye")); return OK; }
            case "login" -> { return runLogin(); }
            case "profile" -> { return runProfile(rest); }
            case "trends" -> { return runTrends(); }
            case "search" -> { return runSearch(rest); }
            case "candidates" -> { return runCandidates(rest); }
            case "discover" -> { return runDiscover(rest); }
            case "prefs" -> { return runPrefs(rest); }
            case "feedback" -> { return runFeedback(rest); }
            case "create" -> { return runCreate(rest); }
            case "playlists" -> { return runPlaylists(); }
            case "show" -> { return runShow(rest); }
            case "modify" -> { return runModify(rest); }
            case "undo" -> { return runUndo(rest); }
            case "validate" -> { return runValidate(rest); }
            case "explain" -> { return runExplain(rest); }
            case "history" -> { return runHistory(rest); }
            case "restore" -> { return runRestore(rest); }
            default -> {
                err.println(String.format(config.wording("cliUnknownCommand"), args[0]));
                printHelp();
                return USAGE;
            }
        }
    }

    /** Reads one command per line until {@code exit} or the end of input; the session ends with status 0. */
    private int interactive() {
        printHelp();
        while (true) {
            out.print(config.wording("cliPrompt") + " ");
            out.flush();
            String line = readLine();
            if (line == null) {
                out.println();
                return OK;
            }
            String[] words = tokenize(line);
            if (words.length == 0) {
                continue;
            }
            int status = dispatch(words);
            if (words[0].equalsIgnoreCase("exit")) {
                return OK;
            }
            if (status != OK) {
                out.println();
            }
        }
    }

    /** Splits a line into words; text in double or single quotes is one word. */
    static String[] tokenize(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started || current.length() > 0) {
            words.add(current.toString());
        }
        return words.toArray(String[]::new);
    }

    // ---- commands ----

    private int runLogin() {
        User user = signIn();
        if (user == null) {
            return FAILED;
        }
        out.println(String.format(config.wording("cliLoggedIn"), user.id()));
        return OK;
    }

    /** F1 — View Taste Profile: {@code profile [--refresh] [--range short|medium|long]}. */
    private int runProfile(String[] rest) {
        boolean refresh = false;
        String range = null;
        for (int i = 0; i < rest.length; i++) {
            switch (rest[i]) {
                case "--refresh" -> refresh = true;
                case "--range" -> {
                    if (i + 1 >= rest.length) {
                        return usage("profile", String.format(config.wording("cliOptionNeedsValue"), "--range"));
                    }
                    range = rest[++i];
                }
                default -> {
                    return usage("profile", String.format(config.wording("cliUnknownOption"), "profile", rest[i]));
                }
            }
        }
        User user = signIn();
        if (user == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            // R5: show the stored profile unless asked to rebuild; picking a range builds that range.
            Optional<ListeningProfile> stored = refresh || range != null ? Optional.empty() : c.lastProfile(user);
            if (stored.isPresent()) {
                out.println(w("StoredProfile"));
                printProfile(stored.get());
            } else {
                out.println(w("BuildingProfile"));
                printProfile(range == null ? c.analyzeTaste(user) : c.analyzeTaste(user, range));
            }
            printMemoryWarning(null);
            return OK;
        } catch (IllegalArgumentException e) {
            return usage("profile", e.getMessage());
        } catch (RuntimeException e) {
            return failure("build your profile", e);
        }
    }

    /** F2 — taste change across the short, medium and long ranges. */
    private int runTrends() {
        User user = signIn();
        if (user == null) {
            return FAILED;
        }
        out.println(w("ComparingTrends"));
        try {
            printTrends(controller().analyzeTrends(user));
            return OK;
        } catch (RuntimeException e) {
            return failure("compare your listening", e);
        }
    }

    /** F3 — natural-language search: {@code search "<query>"}. */
    private int runSearch(String[] rest) {
        String query = String.join(" ", rest).strip();
        if (query.isEmpty()) {
            return usage("search", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            List<Track> found = c.searchMusic(query);
            out.println();
            for (int i = 0; i < found.size(); i++) {
                out.printf("%2d. %s  (%s)%n", i + 1, found.get(i).title(), found.get(i).uri());
            }
            if (!c.lastSearchArtists().isEmpty()) {
                out.println(w("ArtistsFound"));
                c.lastSearchArtists().forEach(a -> out.println("  " + a.name() + "  (" + a.id() + ")"));
            }
            if (c.lastSearchLabel() != null) {
                out.println(c.lastSearchLabel());
            }
            if (c.lastSearchNotice() != null) {
                out.println(c.lastSearchNotice());
            }
            out.println();
            if (!found.isEmpty()) {
                out.println(config.wording("cliUsagePrefix") + " " + config.wording("cliUsage.candidates"));
            }
            out.println(c.spotifyAttribution());
            return OK;
        } catch (RuntimeException e) {
            return failure("search", e);
        }
    }

    /** F3 — {@code candidates list}, {@code add <trackId>}, {@code remove <trackId>}, {@code clear}. */
    private int runCandidates(String[] rest) {
        String action = rest.length == 0 ? "" : rest[0].toLowerCase();
        boolean valid = switch (action) {
            case "list", "clear" -> rest.length == 1;
            case "add", "remove" -> rest.length == 2;
            default -> false;
        };
        if (!valid) {
            return usage("candidates", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            switch (action) {
                case "add" -> c.addCandidate(rest[1]);
                case "remove" -> c.removeCandidate(rest[1]);
                case "clear" -> c.clearCandidates();
                default -> {
                    List<Track> tracks = c.getCandidates();
                    out.println(tracks.isEmpty() ? w("NoCandidates") : w("CandidatesHeading"));
                    for (int i = 0; i < tracks.size(); i++) {
                        out.printf("%2d. %s  (%s)%n", i + 1, tracks.get(i).title(), tracks.get(i).id());
                    }
                    printMemoryWarning(null);
                    return OK;
                }
            }
            if (c.memory().warning() != null) {
                err.println(String.format(config.wording("cliNotSaved"), c.memory().warning()));
                return FAILED;
            }
            out.println(switch (action) {
                case "add" -> w("CandidateAdded", rest[1]);
                case "remove" -> w("CandidateRemoved", rest[1]);
                default -> w("CandidatesCleared");
            });
            return OK;
        } catch (IllegalArgumentException e) {
            return usage("candidates", e.getMessage());
        } catch (RuntimeException e) {
            return failure("change your candidates", e);
        }
    }

    /** F4 — personalised discovery: {@code discover [--novelty 0.3]}. */
    private int runDiscover(String[] rest) {
        Double novelty = null;
        for (int i = 0; i < rest.length; i++) {
            if (!rest[i].equals("--novelty")) {
                return usage("discover", String.format(config.wording("cliUnknownOption"), "discover", rest[i]));
            }
            if (i + 1 >= rest.length) {
                return usage("discover", String.format(config.wording("cliOptionNeedsValue"), "--novelty"));
            }
            try {
                novelty = Double.parseDouble(rest[++i]);
            } catch (NumberFormatException e) {
                return usage("discover", String.format(config.wording("cliBadNumber"), "--novelty", rest[i]));
            }
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            PlaylistSpec spec = c.discoverSpec(novelty);
            out.println(w("FindingMusic"));
            List<Recommendation> recs = c.discover(spec);
            out.println();
            for (int i = 0; i < recs.size(); i++) {
                Recommendation r = recs.get(i);
                out.printf("%2d. %s  (%s)  score %.2f%n    %s%n", i + 1, r.track().title(),
                        r.track().id(), r.score(), r.reason());
            }
            c.lastDiscoverNotices().forEach(this::printNotice);
            if (!recs.isEmpty()) {
                out.println();
                out.println(config.wording("cliUsagePrefix") + " " + config.wording("cliUsage.feedback"));
                out.println(c.spotifyAttribution());
            }
            return OK;
        } catch (IllegalArgumentException e) {
            return usage("discover", e.getMessage());
        } catch (RuntimeException e) {
            return failure("find recommendations", e);
        }
    }

    /**
     * F5 and F11 — {@code create "<goal>" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name "..."]}.
     * A request that cannot be met as stated is put to the user as a numbered prompt.
     */
    private int runCreate(String[] rest) {
        String goal = null;
        int minutes = 0;
        boolean noExplicit = false;
        double familiar = Double.NaN;
        String name = null;
        for (int i = 0; i < rest.length; i++) {
            try {
                switch (rest[i]) {
                    case "--minutes" -> minutes = Integer.parseInt(valueOf(rest, ++i, "--minutes"));
                    case "--no-explicit" -> noExplicit = true;
                    case "--familiar" -> familiar = Double.parseDouble(valueOf(rest, ++i, "--familiar"));
                    case "--name" -> name = valueOf(rest, ++i, "--name");
                    default -> {
                        if (rest[i].startsWith("--") || goal != null) {
                            return usage("create", String.format(config.wording("cliUnknownOption"), "create", rest[i]));
                        }
                        goal = rest[i];
                    }
                }
            } catch (NumberFormatException e) {
                return usage("create", String.format(config.wording("cliBadNumber"), rest[i - 1], rest[i]));
            } catch (IllegalArgumentException e) {
                return usage("create", e.getMessage());
            }
        }
        if (goal == null) {
            return usage("create", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        // Flags that were not given stay unset (0 minutes, NaN, null) and leave the goal's own values alone.
        PlaylistSpec flags = new PlaylistSpec(minutes, !noExplicit, familiar, List.of(), name);
        c.setProgressListener(state -> out.println(w("Progress", state)));
        c.setNegotiationHandler(this::askToRelax);
        try {
            Playlist playlist = c.createPlaylist(new AgentRequest(goal, RequestKind.CREATE_PLAYLIST, flags));
            AgentSession session = c.lastSession();
            out.println();
            out.println(w("Created", playlist.name(), playlist.spotifyId(), playlist.tracks().size()));
            for (int i = 0; i < playlist.tracks().size(); i++) {
                Track t = playlist.tracks().get(i);
                out.printf("%2d. %s  (%s)%n", i + 1, t.title(), t.id());
            }
            session.notices().forEach(this::printNotice);
            out.println();
            out.println(session.explanation());
            out.println(c.spotifyAttribution());
            return OK;
        } catch (RuntimeException e) {
            return failure("create the playlist", e);
        }
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value.");
        }
        return args[index];
    }

    /** F11 prompt: lists the conflicts and options; a number picks one, anything else cancels. */
    private Optional<Relaxation> askToRelax(FeasibilityReport report, List<String> options) {
        out.println();
        report.conflicts().forEach(c -> out.println(config.wording("cliProblemPrefix") + " " + c));
        for (int i = 0; i < options.size(); i++) {
            out.println("  " + (i + 1) + ") " + options.get(i));
        }
        out.print(String.format(config.wording("cliChoosePrompt"), options.size()) + " ");
        out.flush();
        try {
            String line = in.readLine();
            int choice = line == null ? -1 : Integer.parseInt(line.strip());
            return choice >= 1 && choice <= report.relaxations().size()
                    ? Optional.of(report.relaxations().get(choice - 1)) : Optional.empty();
        } catch (NumberFormatException | IOException e) {
            return Optional.empty();
        }
    }

    /** F8 — {@code prefs list}, {@code prefs get <key>}, {@code prefs set <key> <value>}. */
    private int runPrefs(String[] rest) {
        String action = rest.length == 0 ? "" : rest[0].toLowerCase();
        boolean valid = switch (action) {
            case "list" -> rest.length == 1;
            case "get" -> rest.length == 2;
            case "set" -> rest.length >= 3;
            default -> false;
        };
        if (!valid) {
            err.println(config.wording("cliKeys"));
            return usage("prefs", null);
        }
        AppController c = controller();
        if (c.isSpotifyConfigured() && signIn() == null) {
            return FAILED;
        }
        try {
            switch (action) {
                case "set" -> {
                    UserPreference pref = c.parsePreference(rest[1],
                            String.join(" ", Arrays.copyOfRange(rest, 2, rest.length)));
                    c.setPreference(pref);
                    if (c.memory().warning() != null) {
                        err.println(String.format(config.wording("cliNotSaved"), c.memory().warning()));
                        return FAILED;
                    }
                    out.println(w("Saved", pref.key(), c.displayPreference(pref)));
                }
                case "get" -> {
                    List<UserPreference> found = c.getPreferences().stream()
                            .filter(p -> p.key().equals(rest[1])).toList();
                    if (found.isEmpty()) {
                        out.println(w("NoSuchPreference", rest[1]));
                    }
                    found.forEach(p -> out.println(p.key() + " = " + c.displayPreference(p)));
                    printMemoryWarning(w("ShowingDefaults"));
                }
                default -> {
                    List<UserPreference> all = c.getPreferences();
                    if (all.isEmpty()) {
                        out.println(w("NoPreferences"));
                    }
                    all.forEach(p -> out.println(p.key() + " = " + c.displayPreference(p)));
                    printMemoryWarning(w("ShowingDefaults"));
                }
            }
            return OK;
        } catch (IllegalArgumentException e) {
            return usage("prefs", e.getMessage());
        } catch (RuntimeException e) {
            return failure("use preferences", e);
        }
    }

    /** F9 — {@code feedback <trackId> accept|reject}; the id must look like a Spotify track id or URI. */
    private int runFeedback(String[] rest) {
        Verdict verdict = rest.length == 2 ? switch (rest[1].toLowerCase()) {
            case "accept" -> Verdict.ACCEPT;
            case "reject" -> Verdict.REJECT;
            default -> null;
        } : null;
        if (verdict == null) {
            return usage("feedback", null);
        }
        AppController c = controller();
        try {
            c.requireTrackId(rest[0]);   // before signing in: a bad id needs no Spotify
        } catch (IllegalArgumentException e) {
            return usage("feedback", e.getMessage());
        }
        if (c.isSpotifyConfigured() && signIn() == null) {
            return FAILED;
        }
        try {
            c.recordFeedback(rest[0], verdict);
            if (c.memory().warning() != null) {
                err.println(String.format(config.wording("cliNotSaved"), c.memory().warning()));
                return FAILED;
            }
            out.println(w("Recorded", verdict.name().toLowerCase(), rest[0]));
            return OK;
        } catch (IllegalArgumentException e) {
            return usage("feedback", e.getMessage());
        } catch (RuntimeException e) {
            return failure("record your feedback", e);
        }
    }

    /** F6 — the playlists that can be opened and changed, with the ids the other commands take. */
    private int runPlaylists() {
        if (signIn() == null) {
            return FAILED;
        }
        try {
            List<PlaylistSummary> playlists = controller().getPlaylists();
            out.println(playlists.isEmpty() ? w("NoPlaylists") : w("PlaylistsHeading"));
            playlists.forEach(p -> out.println("  " + w("PlaylistLine", p.name(), p.id(), p.trackCount())));
            return OK;
        } catch (RuntimeException e) {
            return failure("list your playlists", e);
        }
    }

    /** F6 — {@code show <playlistId>}. */
    private int runShow(String[] rest) {
        if (rest.length != 1) {
            return usage("show", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        try {
            Playlist playlist = controller().getPlaylist(rest[0]);
            long minutes = Math.round(playlist.totalDuration() / 60_000.0);
            out.println(w("PlaylistHeading", playlist.name(), playlist.spotifyId(), playlist.tracks().size(), minutes));
            for (int i = 0; i < playlist.tracks().size(); i++) {
                Track t = playlist.tracks().get(i);
                out.printf("%2d. %s  (%s)%n", i + 1, t.title(), t.id());
            }
            out.println(controller().spotifyAttribution());
            return OK;
        } catch (RuntimeException e) {
            return failure("show the playlist", e);
        }
    }

    /** F6 — {@code modify <playlistId> "<instruction>"}. */
    private int runModify(String[] rest) {
        if (rest.length != 2) {
            return usage("modify", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        c.setNegotiationHandler(this::askToRelax);
        try {
            Playlist playlist = c.modifyPlaylist(rest[0], rest[1]);
            out.println(config.wording("cliChangedPrefix") + " " + c.lastChangeSummary());
            c.lastModifyNotices().forEach(this::printNotice);
            out.println(w("PlaylistNow", playlist.tracks().size(), rest[0]));
            out.println(c.spotifyAttribution());
            return OK;
        } catch (RuntimeException e) {
            return failure("modify the playlist", e);
        }
    }

    /** F6 — {@code undo <playlistId>}. */
    private int runUndo(String[] rest) {
        if (rest.length != 1) {
            return usage("undo", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        try {
            controller().undo(rest[0]);
            out.println(w("Undone"));
            return OK;
        } catch (RuntimeException e) {
            return failure("undo", e);
        }
    }

    /** F7 — {@code validate <playlistId>}; a failing playlist gives status 1 so scripts can tell. */
    private int runValidate(String[] rest) {
        if (rest.length != 1) {
            return usage("validate", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            ValidationResult result = c.validatePlaylist(rest[0]);
            out.println(c.lastValidationNote());
            if (result.passed()) {
                out.println(config.wording("cliPass"));
                return OK;
            }
            out.println(config.wording("cliFail"));
            result.violations().forEach(v -> out.println("  - " + v));
            result.offending().forEach((rule, ids) -> {
                if (!ids.isEmpty()) {
                    out.println("  " + rule + ": " + String.join(", ", ids));
                }
            });
            return FAILED;
        } catch (RuntimeException e) {
            return failure("validate the playlist", e);
        }
    }

    /** F10 — {@code explain <playlistId>} (a session id works too). */
    private int runExplain(String[] rest) {
        if (rest.length != 1) {
            return usage("explain", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        try {
            out.println(controller().explain(rest[0]));
            return OK;
        } catch (RuntimeException e) {
            return failure("explain the playlist", e);
        }
    }

    /** F12 — past sessions and the saved playlist versions that {@code restore} accepts, or one session. */
    private int runHistory(String[] rest) {
        if (rest.length > 1) {
            return usage("history", null);
        }
        User user = signIn();
        if (user == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            if (rest.length == 1) {
                printSession(c.getSession(rest[0]));
                return OK;
            }
            List<AgentSession> sessions = c.getHistory(user);
            out.println(w("Sessions"));
            if (sessions.isEmpty()) {
                out.println("  " + w("None"));
            }
            for (AgentSession s : sessions) {
                String request = s.request() == null ? "" : s.request().kind() + ": " + s.request().rawText();
                out.println("  " + s.id() + "  " + s.startedAt() + "  " + s.status() + "  " + request
                        + (s.playlistId() == null ? "" : "  " + w("SessionPlaylist", s.playlistId())));
            }
            out.println(w("SavedVersions"));
            List<SnapshotInfo> snapshots = c.getSnapshots(user);
            if (snapshots.isEmpty()) {
                out.println("  " + w("None"));
            }
            for (SnapshotInfo i : snapshots) {
                out.println("  " + i.id() + "  " + i.createdAt() + "  " + w("VersionLine", i.playlistId(), i.name(), i.trackCount()));
            }
            printMemoryWarning(w("HistoryIncomplete"));
            return OK;
        } catch (RuntimeException e) {
            return failure("show the history", e);
        }
    }

    private void printSession(AgentSession s) {
        out.println(w("SessionHeading", s.id()));
        if (s.request() != null) {
            out.println(w("SessionRequest", s.request().rawText()));
        }
        out.println(w("SessionStatus", s.status()));
        out.println(w("SessionStarted", s.startedAt()));
        out.println(s.planSteps().isEmpty() ? w("SessionNoPlan") : w("SessionPlan"));
        for (int i = 0; i < s.planSteps().size(); i++) {
            out.printf("  %d. %s%n", i + 1, s.planSteps().get(i));
        }
        if (s.playlistId() == null) {
            out.println(w("SessionNoResult"));
            return;
        }
        String result = w("SessionPlaylist", s.playlistId());
        OptionalInt tracks = SessionRecord.trackCount(s.derivedData());
        out.println(w("SessionResult", tracks.isPresent() ? result + ", " + w("SessionTracks", tracks.getAsInt()) : result));
    }

    /** F12 — {@code restore <snapshotId>}; offers to recreate a playlist that was deleted. */
    private int runRestore(String[] rest) {
        int snapshotId;
        try {
            snapshotId = rest.length == 1 ? Integer.parseInt(rest[0]) : -1;
        } catch (NumberFormatException e) {
            snapshotId = -1;
        }
        if (snapshotId < 0) {
            return usage("restore", null);
        }
        if (signIn() == null) {
            return FAILED;
        }
        AppController c = controller();
        try {
            Playlist restored = c.restore(snapshotId);
            if (c.lastRestoreChanged()) {
                out.println(w("Restored", restored.name(), restored.spotifyId(), restored.tracks().size(), restored.spotifyId()));
            } else {
                out.println(w("RestoredUnchanged", restored.name(), restored.spotifyId()));
            }
            return OK;
        } catch (PlaylistMissingException e) {
            out.println(e.getMessage());
            if (!confirm(config.wording("cliRecreatePrompt") + " ")) {
                return FAILED;
            }
            try {
                Playlist created = c.recreateFromSnapshot(snapshotId);
                out.println(w("Recreated", created.name(), created.spotifyId(), created.tracks().size()));
                return OK;
            } catch (RuntimeException failure) {
                return failure("recreate the playlist", failure);
            }
        } catch (RuntimeException e) {
            return failure("restore the playlist", e);
        }
    }

    private boolean confirm(String prompt) {
        out.print(prompt);
        out.flush();
        String answer = readLine();
        return answer != null && answer.strip().equalsIgnoreCase("y");
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ---- shared steps ----

    /** The controller, built on first use and kept for the rest of the session. */
    private AppController controller() {
        if (controller == null) {
            controller = factory.get();
        }
        return controller;
    }

    /** Logs in; prints why and returns null if that is not possible. The browser warning shows only when it will open. */
    private User signIn() {
        AppController c = controller();
        if (!c.isSpotifyConfigured()) {
            err.println(config.wording("cliNotConfigured"));
            return null;
        }
        if (c.willOpenBrowser()) {
            out.println(config.wording("cliBrowserLogin"));
        }
        try {
            return c.login();
        } catch (RuntimeException e) {
            failure("log in to Spotify", e);
            return null;
        }
    }

    /** Prints the usage of a command, preceded by what was wrong with the arguments; always status 2. */
    private int usage(String command, String problem) {
        if (problem != null) {
            err.println(problem);
        }
        err.println(config.wording("cliUsagePrefix") + " " + config.wording("cliUsage." + command));
        return USAGE;
    }

    private int failure(String action, RuntimeException e) {
        if (e instanceof AuthExpiredException) {
            err.println(config.wording("cliLoginExpired"));
        } else {
            err.println(String.format(config.wording("cliFailed"), action, e.getMessage()));
        }
        return FAILED;
    }

    /** The wording for {@code text.cli<key>}, filled in with {@code args}. */
    private String w(String key, Object... args) {
        String template = config.wording("cli" + key);
        return args.length == 0 ? template : String.format(template, args);
    }

    private void printNotice(String notice) {
        out.println(config.wording("cliNoticePrefix") + " " + notice);
    }

    private void printHelp() {
        out.println(config.wording("cliHelpTitle"));
        for (String command : COMMANDS) {
            out.println("  " + config.wording("cliUsage." + command));
            out.println("      " + config.wording("cliDesc." + command));
        }
        out.println();
        out.println(config.wording("cliHelpFooter"));
    }

    private void printMemoryWarning(String consequence) {
        String warning = controller().memory().warning();
        if (warning != null) {
            out.println(consequence == null ? String.format(config.wording("cliNotSaved"), warning)
                    : "(" + consequence + ": " + warning + ")");
        }
    }

    // ---- output ----

    private void printProfile(ListeningProfile profile) {
        AppController c = controller();
        out.println();
        out.println(w("ProfileHeading"));
        if (profile.generatedAt() != null) {
            out.println(w("Generated", profile.generatedAt()));
        }
        if (profile.range() != null) {
            out.println(w("TimeRange", profile.range()));
        }
        profile.notices().forEach(this::printNotice);
        out.println();

        out.println(w("TopGenres"));
        List<Map.Entry<String, Double>> genres = profile.rankedGenres(GENRES_SHOWN);
        if (genres.isEmpty()) {
            out.println("  " + w("NoGenreData"));
        }
        for (Map.Entry<String, Double> g : genres) {
            out.printf("  %-24s %5.1f%%%n", g.getKey(), g.getValue() * 100);
        }
        out.println();

        String artists = profile.topArtists().stream().map(Artist::name).filter(n -> !n.isBlank())
                .limit(ARTISTS_SHOWN).collect(Collectors.joining(", "));
        out.println(artists.isEmpty()
                ? w("TopArtistsCounted", profile.topArtists().size())
                : w("TopArtists", artists));
        out.println(w("TracksCounted", profile.topTracks().size(), profile.savedTrackIds().size()));
        out.println();
        out.println(w("Summary", profile.summary()));
        out.println();
        out.println(c.spotifyAttribution());
        out.println(c.genreAttribution());
    }

    private void printTrends(TrendReport report) {
        AppController c = controller();
        out.println();
        out.println(w("TrendsHeading"));
        for (String range : c.timeRanges()) {
            List<Artist> artists = report.artistsByRange().getOrDefault(range, List.of());
            String names = artists.stream().map(Artist::name).limit(ARTISTS_SHOWN).collect(Collectors.joining(", "));
            String genres = report.genresByRange().getOrDefault(range, Map.of()).entrySet().stream()
                    .limit(TREND_GENRES_SHOWN).map(Map.Entry::getKey).collect(Collectors.joining(", "));
            out.println(range + ":");
            out.println("  " + w("ArtistsLine", names.isEmpty() ? w("NoData") : names));
            out.println("  " + w("GenresLine", genres.isEmpty() ? w("NoData") : genres));
            if (!artists.isEmpty()) {
                out.println("  " + String.format(config.wording("trendCoverageLine"),
                        report.artistsWithGenres(range), report.artistCount(range)));
            }
        }
        out.println();
        for (RangeChange step : report.steps()) {
            out.println(w("TrendStep", step.from(), step.to()));
            printList("  " + w("GenresAdded"), step.addedGenres());
            printList("  " + w("GenresDropped"), step.droppedGenres());
            printList("  " + w("ArtistsAdded"), names(report, step.addedArtistIds()));
            printList("  " + w("ArtistsDropped"), names(report, step.droppedArtistIds()));
        }
        out.println(w("TrendOverall"));
        printList("  " + w("GenresAdded"), report.addedGenres());
        printList("  " + w("GenresDropped"), report.droppedGenres());
        printList("  " + w("ArtistsAdded"), names(report, report.addedArtistIds()));
        printList("  " + w("ArtistsDropped"), names(report, report.droppedArtistIds()));
        report.gaps().forEach(this::printNotice);
        out.println();
        out.println(report.narrative());
        out.println();
        out.println(c.spotifyAttribution());
        out.println(c.genreAttribution());
    }

    private void printList(String label, List<String> items) {
        out.println(label + ": " + (items.isEmpty() ? w("None") : String.join(", ", items)));
    }

    private static List<String> names(TrendReport report, List<String> ids) {
        Map<String, String> byId = new LinkedHashMap<>();
        report.artistsByRange().values().forEach(list -> list.forEach(a -> byId.put(a.id(), a.name())));
        return ids.stream().map(id -> byId.getOrDefault(id, id)).toList();
    }
}
