package com.musicmind.app.view;

import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.User;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** F4: personalised recommendations with like / not-for-me icons, "+ Add", one "Why these songs?" and "Why this song?" per row. */
public final class DiscoveryView implements TabView {

    /** The recommendations and what the controller said about them, read on the worker straight after the call. */
    private record Found(List<Recommendation> recommendations, List<String> notices, DecisionTrace trace,
                         java.util.Map<String, com.musicmind.domain.Verdict> verdicts) { }

    private final GuiContext ctx;
    private final ViewFormat format;
    /** How many songs wait for the next playlist after an add, and whether the database accepted it. */
    private record Added(int total, String warning) { }

    private final CheckBox usePreference = new CheckBox();
    /** The share of new artists the slider shows (1 minus the familiar share). */
    private final Slider novelty = new Slider(0, 1, 0);
    /** The saved familiar share, or the configured default until one is read. */
    private final DoubleProperty savedFamiliar = new SimpleDoubleProperty();
    /** True once the user has pressed or dragged the slider. */
    private boolean sliderTouched;
    private final Label mixLabel = new Label();
    private final VBox sliderBox = new VBox();
    private final Button discover;
    private final StatusLine status = new StatusLine();
    private final StatusLine feedbackStatus = new StatusLine();
    private final VBox list = new VBox(UiParts.GAP);
    private final VBox listNotes = new VBox(UiParts.GAP);
    private final Node root;

    public DiscoveryView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        double defaultShare = ctx.config().discoverDefaultFamiliarRatio();
        savedFamiliar.set(defaultShare);
        novelty.setValue(1 - defaultShare);
        usePreference.setText(format.w("NoveltyFromPreference"));
        usePreference.setSelected(true);
        novelty.pressedProperty().addListener((o, was, now) -> sliderTouched |= now);
        novelty.valueChangingProperty().addListener((o, was, now) -> sliderTouched |= now);
        mixLabel.getStyleClass().add("mm-mix-label");
        mixLabel.textProperty().bind(Bindings.createStringBinding(
                () -> format.mixLabel(usePreference.isSelected() ? savedFamiliar.get() : 1 - novelty.getValue()),
                usePreference.selectedProperty(), savedFamiliar, novelty.valueProperty()));
        Label known = UiParts.muted(format.w("MixKnown"));
        Label fresh = UiParts.muted(format.w("MixNew"));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        sliderBox.getChildren().addAll(novelty, new HBox(known, gap, fresh));
        sliderBox.getStyleClass().add("mm-slider-box");
        sliderBox.visibleProperty().bind(usePreference.selectedProperty().not());
        sliderBox.managedProperty().bind(sliderBox.visibleProperty());
        discover = new Button(format.w("DiscoverButton"));
        discover.getStyleClass().add("mm-primary");
        discover.setDefaultButton(true);
        discover.setOnAction(e -> run());

        HBox top = new HBox(UiParts.GAP, usePreference, discover);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox controls = new VBox(UiParts.GAP, top, mixLabel, sliderBox);
        list.getChildren().add(UiParts.muted(format.w("NoRecommendations")));
        ScrollPane scroll = new ScrollPane(new VBox(UiParts.GAP, listNotes, list));
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(480);
        VBox page = UiParts.page(format.w("Menu.discover"), format.w("Desc.discover"), format.w("Example.discover"), controls, status, feedbackStatus, scroll);
        root = new LoginGate(ctx, page, false);
    }

    @Override
    public String title() {
        return format.w("Menu.discover");
    }

    @Override
    public String description() {
        return format.w("Desc.discover");
    }

    @Override
    public String example() {
        return format.w("Example.discover");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        status.info("");
        loadSavedMix();
    }

    @Override
    public void onShown() {
        if (ctx.userProperty().get() != null) {
            loadSavedMix();
        }
    }

    /**
     * Reads the saved mix off the UI thread. Nothing saved means the configured default (so a second user
     * never sees the first one's value). The slider is moved only while "usual mix" is ticked or it has not
     * been touched, so a drag made before the read returns is kept.
     */
    private void loadSavedMix() {
        ctx.run(status, "ActionLoadMix", format.w("Working"), () -> ctx.controller().savedFamiliarBias(), saved -> {
            double share = saved.orElse(ctx.config().discoverDefaultFamiliarRatio());
            savedFamiliar.set(share);
            if (usePreference.isSelected() || !sliderTouched) {
                novelty.setValue(1 - share);
            }
        });
    }

    private void run() {
        Double chosen = usePreference.isSelected() ? null : novelty.getValue();
        ctx.run(status, "ActionDiscover", format.w("Discovering"), () -> {
            PlaylistSpec spec = ctx.controller().discoverSpec(chosen);
            List<Recommendation> recommendations = ctx.controller().discover(spec);
            return new Found(recommendations, ctx.controller().lastDiscoverNotices(), ctx.controller().lastDecisionTrace(),
                    ctx.controller().feedbackFor());
        }, this::show, discover);
    }

    private void show(Found found) {
        listNotes.getChildren().clear();
        found.notices().forEach(n -> listNotes.getChildren().add(UiParts.muted(n)));
        list.getChildren().clear();
        if (found.recommendations().isEmpty()) {
            list.getChildren().add(UiParts.muted(format.w("NoRecommendations")));
            return;
        }
        List<String> traceLines = format.traceLines(found.trace());
        if (!traceLines.isEmpty()) {
            listNotes.getChildren().add(WhyExpander.of(format, "WhyTheseSongs", String.join("\n", traceLines)));
        }
        for (Recommendation rec : found.recommendations()) {
            list.getChildren().add(card(rec, found.verdicts()));
        }
    }

    private VBox card(Recommendation rec, java.util.Map<String, com.musicmind.domain.Verdict> verdicts) {
        Label familiarity = UiParts.muted(format.familiarityLabel(rec.familiarity()));
        VBox card = new VBox(4, TrackRow.of(ctx, feedbackStatus, rec.track(), TrackRow.Options.feedbackOnly().withAdd(this::add),
                verdicts.get(rec.track().id())), familiarity,
                WhyExpander.of(format, "WhyThisSong", rec.reason()));
        card.getStyleClass().add("mm-card");
        return card;
    }

    /** Puts a recommended song on the list that waits for the next playlist. */
    private void add(Track track) {
        ctx.run(feedbackStatus, "ActionChangeCandidates", format.w("Working"), () -> {
            ctx.controller().addCandidate(track.id());
            return new Added(ctx.controller().getCandidates().size(), ctx.controller().memory().warning());
        }, added -> {
            if (added.warning() == null) {
                feedbackStatus.info(format.candidateAdded(track.title(), added.total()));
            }
        });
    }
}
