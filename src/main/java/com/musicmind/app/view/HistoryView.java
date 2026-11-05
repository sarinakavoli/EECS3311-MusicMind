package com.musicmind.app.view;

import com.musicmind.agent.command.PlaylistMissingException;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.SnapshotInfo;
import com.musicmind.domain.SnapshotPreview;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F12: what MusicMind did, newest first (quick looks hidden unless asked for), and the saved versions of each
 * playlist, grouped under its name. A version opens a preview of its songs before it is restored.
 */
public final class HistoryView implements TabView {

    private record Loaded(List<AgentSession> sessions, List<SnapshotInfo> snapshots, String warning) { }

    /** What a restore did; {@code missing} is set when the Spotify playlist was deleted and can be recreated. */
    private record Outcome(String message, String missing) { }

    private static final int PAGE_SIZE = 25;

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Button refresh;
    private final StatusLine status = new StatusLine();
    private final CheckBox showRoutine = new CheckBox();
    /** The timeline order. */
    private enum SortOrder { NEWEST, OLDEST }

    private final ComboBox<SortOrder> sort = new ComboBox<>();
    private final ComboBox<ViewFormat.ActivityType> type = new ComboBox<>();
    private final VBox timeline = new VBox(2);
    private final Button more;
    private final VBox versions = new VBox(UiParts.GAP);
    private final Node root;
    private List<AgentSession> loadedSessions = List.of();
    private int shown = PAGE_SIZE;
    /** True from the moment a restore is chosen until it ends, so a second one cannot queue behind it. */
    private final AtomicBoolean restoring = new AtomicBoolean();

    public HistoryView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        refresh = new Button(format.w("Refresh"));
        refresh.setOnAction(e -> load(ctx.userProperty().get()));
        ctx.onDataChanged(() -> {
            if (ctx.userProperty().get() != null) {
                load(ctx.userProperty().get());
            }
        });
        showRoutine.setText(format.w("ShowRoutine"));
        showRoutine.setOnAction(e -> refilter());
        sort.getItems().setAll(SortOrder.values());
        sort.setValue(SortOrder.NEWEST);
        sort.setConverter(new StringConverter<>() {
            @Override
            public String toString(SortOrder order) {
                return order == SortOrder.OLDEST ? format.w("SortOldest") : format.w("SortNewest");
            }

            @Override
            public SortOrder fromString(String text) {
                return format.w("SortOldest").equals(text) ? SortOrder.OLDEST : SortOrder.NEWEST;
            }
        });
        sort.setOnAction(e -> refilter());
        type.getItems().setAll(ViewFormat.ActivityType.ALL, ViewFormat.ActivityType.CREATED,
                ViewFormat.ActivityType.CHANGED, ViewFormat.ActivityType.RESTORED, ViewFormat.ActivityType.TASTE);
        type.setValue(ViewFormat.ActivityType.ALL);
        type.setConverter(new StringConverter<>() {
            @Override
            public String toString(ViewFormat.ActivityType chosen) {
                return chosen == null ? "" : format.activityTypeLabel(chosen);
            }

            @Override
            public ViewFormat.ActivityType fromString(String text) {
                return ViewFormat.ActivityType.ALL;
            }
        });
        type.setOnAction(e -> refilter());
        more = new Button(format.w("ShowMore"));
        more.setOnAction(e -> {
            shown += PAGE_SIZE;
            fillTimeline();
        });

        VBox page = UiParts.page(format.w("Menu.history"), format.w("Desc.history"), "", controls(), status,
                UiParts.heading(format.w("HistoryTitle")), timeline, more,
                UiParts.heading(format.w("SavedVersions")), UiParts.muted(format.w("SavedVersionsHint")), versions);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
    }

    private Node controls() {
        HBox bar = new HBox(UiParts.GAP, refresh, UiParts.muted(format.w("SortLabel")), sort,
                UiParts.muted(format.w("TypeLabel")), type, showRoutine);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void refilter() {
        shown = PAGE_SIZE;
        fillTimeline();
    }

    @Override
    public String title() {
        return format.w("Menu.history");
    }

    @Override
    public String description() {
        return format.w("Desc.history");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        load(user);
    }

    private void load(User user) {
        ctx.run(status, "ActionHistory", format.w("Working"), () -> {
            List<AgentSession> past = ctx.controller().getHistory(user);
            List<SnapshotInfo> snapshots = ctx.controller().getSnapshots(user);
            return new Loaded(past, snapshots, ctx.controller().memory().warning());
        }, loaded -> {
            loadedSessions = loaded.sessions();
            fillTimeline();
            fillVersions(loaded.snapshots());
            if (loaded.warning() != null) {
                status.info(format.w("HistoryIncomplete"));
            }
        }, refresh);
    }

    // ---- activity timeline ----

    private void fillTimeline() {
        List<AgentSession> visible = format.activity(loadedSessions, type.getValue(), sort.getValue() != SortOrder.OLDEST, showRoutine.isSelected());
        List<Node> rows = new ArrayList<>();
        for (AgentSession session : visible.subList(0, Math.min(shown, visible.size()))) {
            rows.add(timelineEntry(session));
        }
        if (rows.isEmpty()) {
            boolean routineOnly = !loadedSessions.isEmpty() && type.getValue() == ViewFormat.ActivityType.ALL
                    && !showRoutine.isSelected();
            boolean filtered = !loadedSessions.isEmpty() && type.getValue() != ViewFormat.ActivityType.ALL;
            rows.add(UiParts.muted(format.w(routineOnly ? "HideRoutineHint" : filtered ? "NoActivityOfType" : "NoActivity")));
        }
        timeline.getChildren().setAll(rows);
        more.setVisible(visible.size() > shown);
        more.setManaged(more.isVisible());
    }

    /** One activity line; clicking it opens or closes the session's request, status, plan and result. */
    private Node timelineEntry(AgentSession session) {
        Label when = new Label(session.startedAt() == null ? "" : format.timestamp(session.startedAt()));
        when.getStyleClass().addAll("mm-muted", "mm-when");
        Label kind = new Label(format.kindLabel(session));
        kind.getStyleClass().add("mm-chip");
        Label text = new Label(format.timelineText(session));
        text.setTextOverrun(OverrunStyle.ELLIPSIS);
        text.setMaxWidth(Double.MAX_VALUE);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label arrow = new Label(format.w("OpenArrow"));
        arrow.getStyleClass().add("mm-muted");
        HBox line = new HBox(UiParts.GAP, when, kind, text);
        String flag = format.statusFlag(session);
        if (!flag.isEmpty()) {
            Label state = new Label(flag);
            state.getStyleClass().addAll("mm-badge", "mm-badge-warn");
            line.getChildren().add(state);
        }
        line.getChildren().add(arrow);
        line.getStyleClass().addAll("mm-row", "mm-clickable");
        line.setAlignment(Pos.CENTER_LEFT);
        VBox detail = new VBox(2);
        detail.getStyleClass().add("mm-timeline-detail");
        detail.setVisible(false);
        detail.setManaged(false);
        line.setOnMouseClicked(e -> {
            toggleDetail(session.id(), detail);
            arrow.setText(format.w(detail.isVisible() ? "OpenArrow" : "CloseArrow"));
        });
        return new VBox(line, detail);
    }

    private void toggleDetail(String id, VBox detail) {
        if (detail.isVisible()) {
            detail.setVisible(false);
            detail.setManaged(false);
            return;
        }
        ctx.run(status, "ActionSession", format.w("Working"), () -> ctx.controller().getSession(id), session -> {
            detail.getChildren().setAll(format.sessionLines(session).stream().map(UiParts::wrapped).toList());
            detail.setVisible(true);
            detail.setManaged(true);
        });
    }

    // ---- saved versions ----

    private void fillVersions(List<SnapshotInfo> snapshots) {
        List<Node> groups = new ArrayList<>();
        for (ViewFormat.VersionGroup group : format.versionGroups(snapshots)) {
            Label name = new Label(group.name());
            name.getStyleClass().add("mm-group-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox card = new VBox(2, name);
            card.getStyleClass().add("mm-card");
            for (ViewFormat.VersionLine line : group.versions()) {
                card.getChildren().add(versionRow(line));
            }
            groups.add(card);
        }
        if (groups.isEmpty()) {
            groups.add(UiParts.muted(format.w("NoVersions")));
        }
        versions.getChildren().setAll(groups);
    }

    private Node versionRow(ViewFormat.VersionLine line) {
        Label when = new Label(line.when());
        when.getStyleClass().add("mm-when");
        Label change = UiParts.muted(line.change());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button restore = new Button(format.w("Restore"));
        restore.setOnAction(e -> preview(line.info(), restore));
        HBox row = new HBox(UiParts.GAP, when, change, spacer, restore);
        row.getStyleClass().add("mm-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Reads the version's songs, shows them, and restores only when the user confirms in the preview. */
    private void preview(SnapshotInfo version, Button button) {
        if (!restoring.compareAndSet(false, true)) {
            return;
        }
        ctx.run(status, "ActionPreview", format.w("Working"), () -> {
            try {
                return ctx.controller().previewSnapshot(version.id());
            } catch (RuntimeException e) {
                restoring.set(false);
                throw e;
            }
        }, songs -> {
            if (confirmPreview(version, songs)) {
                restore(version, button);
            } else {
                restoring.set(false);
            }
        }, button);
    }

    private boolean confirmPreview(SnapshotInfo version, SnapshotPreview preview) {
        VBox list = new VBox();
        for (Track song : preview.songs()) {
            Track shownSong = song.title() == null || song.title().isBlank()
                    ? new Track(song.id(), song.uri(), format.w("PreviewNoTitle"), song.durationMs(), false, song.artistIds(), song.artistNames())
                    : song;
            list.getChildren().add(TrackRow.of(ctx, status, shownSong, TrackRow.Options.plain(), null));
        }
        if (preview.songs().isEmpty()) {
            list.getChildren().add(UiParts.muted(format.w("PreviewEmpty")));
        }
        if (preview.moreCount() > 0) {
            list.getChildren().add(UiParts.muted(format.previewMoreLine(preview.moreCount())));
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mm-preview-scroll");
        Label changeNote = UiParts.muted(format.w("PreviewHint"));
        VBox content = new VBox(UiParts.GAP, changeNote, scroll);
        if (preview.stoppedEarly()) {
            content.getChildren().add(1, UiParts.muted(format.w("PreviewStopped")));
        }
        return ConfirmDialog.show(root.getScene() == null ? null : root.getScene().getWindow(),
                format.previewTitle(version), content, List.of(ConfirmDialog.Choice.secondary(format.w("PreviewCancel")),
                        ConfirmDialog.Choice.primary(format.w("PreviewRestore")))) == 1;
    }

    private void restore(SnapshotInfo version, Button button) {
        ctx.run(status, "ActionRestore", format.w("Restoring"), () -> {
            boolean handOver = false;
            try {
                Playlist restored = ctx.controller().restore(version.id());
                return new Outcome(ctx.controller().lastRestoreChanged()
                        ? format.w("Restored", restored.name(), restored.tracks().size())
                        : format.w("RestoredUnchanged", restored.name()), null);
            } catch (PlaylistMissingException e) {
                handOver = true;
                return new Outcome(null, e.getMessage());
            } finally {
                if (!handOver) {
                    restoring.set(false);
                }
            }
        }, outcome -> {
            if (outcome.missing() == null) {
                status.info(outcome.message());
                ctx.dataChanged();
            } else if (confirmRecreate(outcome.missing())) {
                recreate(version, button);
            } else {
                restoring.set(false);
                status.info(outcome.missing());
            }
        }, button);
    }

    private boolean confirmRecreate(String message) {
        return ConfirmDialog.confirm(root.getScene() == null ? null : root.getScene().getWindow(),
                format.w("RecreateTitle"), message + "\n\n" + format.w("RecreatePrompt"), format.w("PreviewCancel"),
                format.w("RecreateYes"), false);
    }

    private void recreate(SnapshotInfo version, Button button) {
        restoring.set(true);
        ctx.run(status, "ActionRecreate", format.w("Restoring"), () -> {
            try {
                Playlist created = ctx.controller().recreateFromSnapshot(version.id());
                return format.w("Recreated", created.name(), created.tracks().size());
            } finally {
                restoring.set(false);
            }
        }, message -> {
            status.info(message);
            ctx.dataChanged();
        }, button);
    }
}
