package com.musicmind.app.view;

import com.musicmind.agent.PreferenceSettings;
import com.musicmind.domain.Artist;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.Verdict;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * F8: the rules MusicMind follows. Explicit songs, the mix and the styles are plain controls with a Save;
 * artists and songs to never use are chosen by name from a list of matches, never typed as ids.
 */
public final class PreferencesView implements TabView {

    /** One blocked artist as the screen lists it. */
    private record BlockedArtist(String id, String name) { }

    /** The stored rules, read on the worker: what each control shows and the warning if the database is down. */
    private record Loaded(boolean explicitAllowed, double familiarShare, String styles,
                          List<BlockedArtist> artists, List<String> songIds, Map<String, String> songLines,
                          String warning) { }

    /** Artists found for a typed name. */
    private record Matches(String typed, List<Artist> artists, Map<String, String> hints) { }

    /** Songs found for the block search. */
    private record Songs(List<Track> tracks, Map<String, Verdict> verdicts) { }

    private final GuiContext ctx;
    private final ViewFormat format;
    private final StatusLine status = new StatusLine();
    private final ToggleGroup explicit = new ToggleGroup();
    private final RadioButton allow = new RadioButton();
    private final RadioButton block = new RadioButton();
    private final Button saveExplicit = new Button();
    /** The share of new artists the slider shows (1 minus the saved familiar share), as on Discover. */
    private final Slider novelty = new Slider(0, 1, 0);
    private final Label mixLabel = new Label();
    private final Button saveMix = new Button();
    private final TextField styles = new TextField();
    private final Button saveStyles = new Button();
    private final TextField artistQuery = new TextField();
    private final Button findArtist = new Button();
    private final Label artistMatches = UiParts.muted("");
    private final VBox artistChoices = new VBox();
    private final VBox blockedArtists = new VBox();
    private final TextField songQuery = new TextField();
    private final Button findSong = new Button();
    private final VBox songChoices = new VBox();
    private final VBox blockedSongs = new VBox();
    /** Titles of songs seen in this session; Spotify content is not stored, so older blocks have none. */
    private final Map<String, String> knownTitles = new HashMap<>();
    /** "Title - Artist" lines read live from Spotify when the screen opened; held in memory only, never stored. */
    private final Map<String, String> songLines = new HashMap<>();
    /** What is saved (or loaded) for each Save button; a button shows only while its control differs. */
    private boolean savedAllowExplicit = true;
    /** The saved familiar share (1 minus what the slider shows). */
    private double savedShare;
    private String savedStyles = "";
    private final Node root;
    /** True while the user has changed the explicit, mix or styles controls and not saved; a reload would overwrite them. */
    private boolean unsaved;

    public PreferencesView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        VBox page = UiParts.page(format.w("Menu.preferences"), format.w("Desc.preferences"), format.w("Example.preferences"),
                status, explicitSection(), mixSection(), artistSection(), songSection(), stylesSection());
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        explicit.selectedToggleProperty().addListener((o, was, now) -> changed());
        novelty.valueProperty().addListener((o, was, now) -> changed());
        styles.textProperty().addListener((o, was, now) -> changed());
        savedShare = 1 - novelty.getValue();
        refreshSaveButtons();
        root = new LoginGate(ctx, scroll, true);
        if (!ctx.spotifyConfigured()) {
            load();
        }
    }

    private void changed() {
        unsaved = true;
        refreshSaveButtons();
    }

    /** Shows each Save only while its control differs from what is saved; hidden ones take no space. */
    private void refreshSaveButtons() {
        showIf(saveExplicit, allow.isSelected() != savedAllowExplicit);
        showIf(saveMix, ViewFormat.shareChanged(savedShare, 1 - novelty.getValue()));
        showIf(saveStyles, ViewFormat.isChanged(savedStyles, styles.getText()));
    }

    private static void showIf(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private Node explicitSection() {
        allow.setText(format.w("ExplicitAllow"));
        block.setText(format.w("ExplicitBlock"));
        allow.setToggleGroup(explicit);
        block.setToggleGroup(explicit);
        allow.setSelected(true);
        saveExplicit.setText(format.w("Save"));
        saveExplicit.setOnAction(e -> savePref(PreferenceSettings.EXPLICIT_POLICY, allow.isSelected() ? "allow" : "block", saveExplicit));
        return section(format.w("ExplicitHeading"), UiParts.muted(format.w("ExplicitHint")), new HBox(UiParts.SPACE_S, allow, block), saveExplicit);
    }

    private Node mixSection() {
        double defaultShare = ctx.config().discoverDefaultFamiliarRatio();
        novelty.setValue(1 - defaultShare);
        novelty.getStyleClass().add("mm-mix-slider");
        mixLabel.getStyleClass().add("mm-mix-label");
        mixLabel.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> format.mixLabel(1 - novelty.getValue()), novelty.valueProperty()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox ends = new HBox(UiParts.muted(format.w("MixKnown")), gap, UiParts.muted(format.w("MixNew")));
        VBox slider = new VBox(novelty, ends);
        slider.getStyleClass().add("mm-slider-box");
        saveMix.setText(format.w("Save"));
        saveMix.setOnAction(e -> savePref(PreferenceSettings.FAMILIAR_BIAS, String.valueOf(Math.round((1 - novelty.getValue()) * 100) / 100.0), saveMix));
        return section(format.w("MixHeading"), UiParts.muted(format.w("MixRuleHint")), mixLabel, slider, saveMix);
    }

    private Node artistSection() {
        artistQuery.setPromptText(format.w("ArtistPrompt"));
        HBox.setHgrow(artistQuery, Priority.ALWAYS);
        findArtist.setText(format.w("FindButton"));
        findArtist.getStyleClass().add("mm-primary");
        findArtist.setOnAction(e -> findArtists());
        artistQuery.setOnAction(e -> findArtists());
        HBox bar = new HBox(UiParts.SPACE_S, artistQuery, findArtist);
        bar.setAlignment(Pos.CENTER_LEFT);
        artistMatches.setManaged(false);
        artistMatches.setVisible(false);
        blockedArtists.getChildren().add(UiParts.muted(format.w("BlockedArtistsNone")));
        return section(format.w("BlockedArtistsHeading"), UiParts.muted(format.w("BlockedArtistsHint")), bar,
                artistMatches, artistChoices, UiParts.heading(format.w("BlockedNow")), blockedArtists);
    }

    private Node songSection() {
        songQuery.setPromptText(format.w("BlockSearchPrompt"));
        HBox.setHgrow(songQuery, Priority.ALWAYS);
        findSong.setText(format.w("SearchButton"));
        findSong.getStyleClass().add("mm-primary");
        findSong.setOnAction(e -> findSongs());
        songQuery.setOnAction(e -> findSongs());
        HBox bar = new HBox(UiParts.SPACE_S, songQuery, findSong);
        bar.setAlignment(Pos.CENTER_LEFT);
        blockedSongs.getChildren().add(UiParts.muted(format.w("BlockedSongsNone")));
        return section(format.w("BlockedSongsHeading"), UiParts.muted(format.w("BlockedSongsHint")), bar, songChoices, UiParts.heading(format.w("BlockedNow")), blockedSongs);
    }

    private Node stylesSection() {
        styles.setPromptText(format.w("StylesPrompt"));
        saveStyles.setText(format.w("Save"));
        saveStyles.setOnAction(e -> savePref(PreferenceSettings.PREFERRED_STYLES,
                styles.getText() == null ? "" : styles.getText().strip(), saveStyles));
        styles.setOnAction(e -> saveStyles.fire());
        HBox.setHgrow(styles, Priority.ALWAYS);
        HBox bar = new HBox(UiParts.SPACE_S, styles, saveStyles);
        bar.setAlignment(Pos.CENTER_LEFT);
        return section(format.w("StylesHeading"), UiParts.muted(format.w("StylesHint")), bar);
    }

    private static VBox section(String heading, Node... children) {
        VBox box = new VBox(UiParts.SPACE_S, UiParts.heading(heading));
        box.getChildren().addAll(children);
        box.getStyleClass().add("mm-section");
        return box;
    }

    @Override
    public String title() {
        return format.w("Menu.preferences");
    }

    @Override
    public String description() {
        return format.w("Desc.preferences");
    }

    @Override
    public String example() {
        return format.w("Example.preferences");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        load();
    }

    @Override
    public void onShown() {
        if (!unsaved && (ctx.userProperty().get() != null || !ctx.spotifyConfigured())) {
            load();
        }
    }

    private Node[] locked() {
        return new Node[] {saveExplicit, saveMix, saveStyles, findArtist, findSong};
    }

    /** Reads the stored rules on the worker and fills every control; blocked artists are looked up by name. */
    private void load() {
        load(null);
    }

    /** As {@link #load()}, then shows {@code doneMessage} (when given) once the lists are up to date. */
    private void load(String doneMessage) {
        ctx.run(status, "ActionShowPrefs", format.w("Working"), () -> {
            List<UserPreference> stored = ctx.controller().getPreferences();
            PreferenceSettings settings = PreferenceSettings.from(stored);
            List<BlockedArtist> artists = new ArrayList<>();
            for (String id : settings.blockedArtists()) {
                String name = ctx.controller().displayPreference(new UserPreference(PreferenceSettings.BLOCKED_ARTISTS, id, false, 0));
                artists.add(new BlockedArtist(id, format.blockedArtistName(id, name)));
            }
            Map<String, String> lines = ctx.controller().trackLines(ViewFormat.firstN(
                    new ArrayList<>(new TreeSet<>(settings.blockedTracks())), ctx.config().guiBlockedTitlesShown()));
            double share = settings.familiarBias().orElse(ctx.config().discoverDefaultFamiliarRatio());
            return new Loaded(settings.explicitAllowed().orElse(true), share, String.join(", ", settings.preferredStyles()),
                    artists, new ArrayList<>(new TreeSet<>(settings.blockedTracks())), lines, ctx.controller().memory().warning());
        }, loaded -> {
            show(loaded);
            if (doneMessage != null && loaded.warning() == null) {
                status.info(doneMessage);
            }
        }, locked());
    }

    private void show(Loaded loaded) {
        savedAllowExplicit = loaded.explicitAllowed();
        savedShare = loaded.familiarShare();
        savedStyles = loaded.styles();
        (loaded.explicitAllowed() ? allow : block).setSelected(true);
        novelty.setValue(1 - loaded.familiarShare());
        styles.setText(loaded.styles());
        unsaved = false;
        refreshSaveButtons();
        songLines.clear();
        songLines.putAll(loaded.songLines());
        fillBlockedArtists(loaded.artists());
        fillBlockedSongs(loaded.songIds());
        if (loaded.warning() != null) {
            status.info(format.w("PrefsDatabaseDown"));
        }
    }

    private void fillBlockedArtists(List<BlockedArtist> artists) {
        if (artists.isEmpty()) {
            blockedArtists.getChildren().setAll(UiParts.muted(format.w("BlockedArtistsNone")));
            return;
        }
        blockedArtists.getChildren().setAll(artists.stream()
                .map(a -> blockedRow(a.name(), () -> removeArtist(a))).toList());
    }

    private void fillBlockedSongs(List<String> ids) {
        if (ids.isEmpty()) {
            blockedSongs.getChildren().setAll(UiParts.muted(format.w("BlockedSongsNone")));
            return;
        }
        blockedSongs.getChildren().setAll(ids.stream()
                .map(id -> blockedRow(format.blockedSongName(songName(id)), () -> removeSong(id))).toList());
    }

    /** The best name known for a blocked song: this session's title, else the live line, else null. */
    private String songName(String id) {
        String title = knownTitles.get(id);
        return title != null ? title : songLines.get(id);
    }

    private Node blockedRow(String name, Runnable remove) {
        Label label = new Label(name);
        label.getStyleClass().add("mm-track-title");
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinWidth(0);
        HBox.setHgrow(label, Priority.ALWAYS);
        Button button = new Button(format.w("RemoveButton"));
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> remove.run());
        HBox row = new HBox(UiParts.SPACE_S, label, button);
        row.getStyleClass().add("mm-row");
        return row;
    }

    // ---- Explicit, mix and styles ----

    private void savePref(String key, String value, Button saveButton) {
        ctx.run(status, "ActionSavePref", format.w("Working"), () -> {
            UserPreference pref = ctx.controller().parsePreference(key, value);
            ctx.controller().setPreference(pref);
            return ctx.controller().memory().warning();
        }, warning -> {
            if (warning != null) {
                status.error(ctx.config().wording("cliNotSaved").formatted(warning));
            } else {
                unsaved = false;
                switch (key) {
                    case PreferenceSettings.EXPLICIT_POLICY -> savedAllowExplicit = value.equals("allow");
                    case PreferenceSettings.FAMILIAR_BIAS -> savedShare = Double.parseDouble(value);
                    case PreferenceSettings.PREFERRED_STYLES -> savedStyles = value;
                    default -> { }
                }
                refreshSaveButtons();
                status.info(format.w(value.isBlank() ? "ClearedPref" : "SavedPref", format.w("Pref." + key)));
            }
        }, saveButton);
    }

    // ---- Blocked artists ----

    private void findArtists() {
        String typed = artistQuery.getText() == null ? "" : artistQuery.getText().strip();
        if (typed.isEmpty()) {
            status.error(ctx.config().wording("prefsArtistBlank"));
            return;
        }
        ctx.run(status, "ActionFindArtists", format.w("Searching"),
                () -> {
                    List<Artist> found = ctx.controller().findArtists(typed);
                    Map<String, String> hints = ctx.controller().artistHints(found.stream()
                            .limit(ctx.config().searchArtistLimit()).map(Artist::id).toList());
                    return new Matches(typed, found, hints);
                }, matches -> {
                    artistMatches.setText(format.artistMatchesLine(matches.typed(), matches.artists().size()));
                    artistMatches.setManaged(true);
                    artistMatches.setVisible(true);
                    List<Node> rows = new ArrayList<>();
                    Map<String, Integer> seen = new HashMap<>();
                    for (Artist artist : matches.artists()) {
                        String key = artist.name().toLowerCase(java.util.Locale.ROOT);
                        long total = matches.artists().stream().filter(o -> o.name().equalsIgnoreCase(artist.name())).count();
                        int position = seen.merge(key, 1, Integer::sum);
                        rows.add(artistChoice(artist, format.artistPosition(position, (int) total), matches.hints().getOrDefault(artist.id(), "")));
                    }
                    artistChoices.getChildren().setAll(rows);
                }, findArtist);
    }

    private Node artistChoice(Artist artist, String position, String hint) {
        Label name = new Label(position.isEmpty() ? artist.name() : artist.name() + "  (" + position + ")");
        name.getStyleClass().add("mm-track-title");
        VBox text = new VBox(UiParts.SPACE_XS, name);
        String detail = hint != null && !hint.isBlank() ? hint
                : artist.genres() == null ? "" : String.join(", ", artist.genres());
        if (!detail.isEmpty()) {
            Label genres = new Label(detail);
            genres.getStyleClass().addAll("mm-muted", "mm-track-artists");
            text.getChildren().add(genres);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button choose = new Button(format.w("BlockButton"));
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(e -> blockArtist(artist));
        HBox row = new HBox(UiParts.SPACE_S, text, choose);
        row.getStyleClass().add("mm-row");
        return row;
    }

    private void blockArtist(Artist artist) {
        runBlock("ActionBlock", () -> ctx.controller().blockArtist(artist.id()),
                format.w("BlockedArtist", artist.name()), () -> {
                    artistChoices.getChildren().clear();
                    artistMatches.setManaged(false);
                    artistMatches.setVisible(false);
                    artistQuery.clear();
                });
    }

    private void removeArtist(BlockedArtist artist) {
        runBlock("ActionRemoveBlock", () -> ctx.controller().unblockArtist(artist.id()),
                format.w("RemovedBlock", artist.name()), () -> { });
    }

    // ---- Blocked songs ----

    private void findSongs() {
        String typed = songQuery.getText() == null ? "" : songQuery.getText().strip();
        if (typed.isEmpty()) {
            status.error(format.w("SongBlank"));
            return;
        }
        ctx.run(status, "ActionFindBlockSongs", format.w("Searching"), () -> {
            List<Track> tracks = ctx.controller().findTracks(typed);
            return new Songs(tracks, ctx.controller().feedbackFor());
        }, found -> {
            if (found.tracks().isEmpty()) {
                songChoices.getChildren().setAll(UiParts.muted(format.w("NoResults")));
                return;
            }
            TrackRow.Options options = TrackRow.Options.plain().withBlock(this::blockSong);
            songChoices.getChildren().setAll(found.tracks().stream()
                    .map(t -> TrackRow.of(ctx, status, t, options, found.verdicts().get(t.id()))).toList());
        }, findSong);
    }

    private void blockSong(Track track) {
        knownTitles.put(track.id(), track.title());
        runBlock("ActionBlock", () -> ctx.controller().blockTrack(track.id()),
                format.w("BlockedSong", track.title()), () -> songChoices.getChildren().clear());
    }

    private void removeSong(String id) {
        String title = format.blockedSongName(songName(id));
        runBlock("ActionRemoveBlock", () -> ctx.controller().unblockTrack(id),
                format.w("RemovedBlock", title), () -> { });
    }

    /** Runs a block or unblock on the worker, shows a plain message when it is refused, then re-reads the rules. */
    private void runBlock(String action, Runnable change, String doneMessage, Runnable afterwards) {
        ctx.run(status, action, format.w("Working"), () -> {
            try {
                change.run();
                return new String[] {null, ctx.controller().memory().warning()};
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new String[] {format.blockFailure(e), null};
            }
        }, result -> {
            if (result[0] != null) {
                status.error(result[0]);
            } else if (result[1] != null) {
                status.error(ctx.config().wording("cliNotSaved").formatted(result[1]));
            } else {
                afterwards.run();
                load(doneMessage);
            }
        }, locked());
    }
}
