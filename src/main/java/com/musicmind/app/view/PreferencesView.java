package com.musicmind.app.view;

import com.musicmind.agent.PreferenceSettings;
import com.musicmind.domain.User;
import com.musicmind.domain.UserPreference;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** F8: a settings screen with one editable row per preference; artist names are looked up on Save. */
public final class PreferencesView implements TabView {

    /** The preferences as they are stored, each already worded for display. */
    private record Loaded(Map<String, String> values, String warning) { }

    /** What a Save did: the stored value as shown, notices from the lookup, and whether the database took it. */
    private record Saved(String key, String shown, boolean cleared, List<String> notices, String warning) { }

    /** The preference keys in the order the screen lists them. */
    private static final List<String> KEYS = List.of(PreferenceSettings.BLOCKED_ARTISTS, PreferenceSettings.BLOCKED_TRACKS,
            PreferenceSettings.EXPLICIT_POLICY, PreferenceSettings.FAMILIAR_BIAS, PreferenceSettings.PREFERRED_STYLES);

    private final GuiContext ctx;
    private final ViewFormat format;
    private final StatusLine status = new StatusLine();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final Label notices = UiParts.muted("");
    private final Node root;

    public PreferencesView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        GridPane grid = new GridPane();
        grid.setHgap(UiParts.GAP);
        grid.setVgap(UiParts.GAP);
        int row = 0;
        for (String key : KEYS) {
            Label label = new Label(format.w("Pref." + key));
            TextField field = new TextField();
            GridPane.setHgrow(field, Priority.ALWAYS);
            Button save = new Button(format.w("Save"));
            save.setOnAction(e -> save(key));
            field.setOnAction(e -> save(key));
            fields.put(key, field);
            buttons.put(key, save);
            grid.add(label, 0, row);
            grid.add(field, 1, row);
            grid.add(save, 2, row);
            row++;
        }
        VBox page = UiParts.page(UiParts.wrapped(format.w("PrefsIntro")), grid, status, notices);
        root = new LoginGate(ctx, page, true);
        if (!ctx.spotifyConfigured()) {
            load();
        }
    }

    @Override
    public String title() {
        return format.w("TabPreferences");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        load();
    }

    private void load() {
        ctx.run(status, "show your preferences", format.w("Working"), () -> {
            List<UserPreference> stored = ctx.controller().getPreferences();
            Map<String, String> values = new LinkedHashMap<>();
            for (UserPreference pref : stored) {
                values.put(pref.key(), ctx.controller().displayPreference(pref));
            }
            return new Loaded(values, ctx.controller().memory().warning());
        }, loaded -> {
            fields.forEach((key, field) -> field.setText(loaded.values().getOrDefault(key, "")));
            if (loaded.warning() != null) {
                status.info(ctx.config().wording("cliShowingDefaults") + ": " + loaded.warning());
            }
        }, buttons.values().toArray(Node[]::new));
    }

    private void save(String key) {
        String text = fields.get(key).getText() == null ? "" : fields.get(key).getText().strip();
        ctx.run(status, "save the preference", format.w("Working"), () -> {
            UserPreference pref = ctx.controller().parsePreference(key, text);
            ctx.controller().setPreference(pref);
            return new Saved(key, ctx.controller().displayPreference(pref), pref.value().isBlank(),
                    ctx.controller().lastPreferenceNotices(), ctx.controller().memory().warning());
        }, saved -> {
            fields.get(key).setText(saved.shown());
            notices.setText(String.join("\n", saved.notices()));
            if (saved.warning() != null) {
                status.error(ctx.config().wording("cliNotSaved").formatted(saved.warning()));
            } else {
                status.info(format.w(saved.cleared() ? "ClearedPref" : "SavedPref", format.w("Pref." + key)));
            }
        }, buttons.values().toArray(Node[]::new));
    }
}
