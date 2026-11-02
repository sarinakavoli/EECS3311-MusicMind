package com.musicmind.app.view;

import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.User;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** F4: personalised recommendations with accept / reject controls and "Why these?" on the list and on each item. */
public final class DiscoveryView implements TabView {

    /** The recommendations and what the controller said about them, read on the worker straight after the call. */
    private record Found(List<Recommendation> recommendations, List<String> notices, DecisionTrace trace) { }

    private final GuiContext ctx;
    private final ViewFormat format;
    private final CheckBox usePreference = new CheckBox();
    private final Slider novelty = new Slider(0, 1, 0.5);
    private final Label noveltyLabel = new Label();
    private final Button discover;
    private final StatusLine status = new StatusLine();
    private final StatusLine feedbackStatus = new StatusLine();
    private final VBox list = new VBox(UiParts.GAP);
    private final VBox listNotes = new VBox(UiParts.GAP);
    private final Node root;

    public DiscoveryView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        usePreference.setText(format.w("NoveltyFromPreference"));
        usePreference.setSelected(true);
        novelty.disableProperty().bind(usePreference.selectedProperty());
        noveltyLabel.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> format.w("NoveltyLabel", novelty.getValue() * 100), novelty.valueProperty()));
        discover = new Button(format.w("DiscoverButton"));
        discover.setDefaultButton(true);
        discover.setOnAction(e -> run());

        HBox controls = new HBox(UiParts.GAP, usePreference, new Label(format.w("Novelty")), novelty, noveltyLabel, discover);
        controls.setAlignment(Pos.CENTER_LEFT);
        list.getChildren().add(UiParts.muted(format.w("NoRecommendations")));
        ScrollPane scroll = new ScrollPane(new VBox(UiParts.GAP, listNotes, list));
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(480);
        VBox page = UiParts.page(controls, status, feedbackStatus, scroll);
        root = new LoginGate(ctx, page, false);
    }

    @Override
    public String title() {
        return format.w("TabDiscover");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        status.info("");
    }

    private void run() {
        Double chosen = usePreference.isSelected() ? null : novelty.getValue();
        ctx.run(status, "ActionDiscover", format.w("Discovering"), () -> {
            PlaylistSpec spec = ctx.controller().discoverSpec(chosen);
            List<Recommendation> recommendations = ctx.controller().discover(spec);
            return new Found(recommendations, ctx.controller().lastDiscoverNotices(), ctx.controller().lastDecisionTrace());
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
            listNotes.getChildren().add(WhyExpander.of(format, String.join("\n", traceLines)));
        }
        for (Recommendation rec : found.recommendations()) {
            list.getChildren().add(card(rec));
        }
    }

    private VBox card(Recommendation rec) {
        Label title = new Label(rec.track().title() + "  (" + format.duration(rec.track().durationMs()) + ")");
        title.setStyle("-fx-font-weight: bold;");
        Label detail = UiParts.muted(format.recommendationDetail(rec));
        VBox card = new VBox(4, title, detail,
                new FeedbackBar(ctx, feedbackStatus, rec.track().id(), rec.track().title()),
                WhyExpander.of(format, rec.reason()));
        card.setStyle("-fx-border-color: #dddddd; -fx-border-radius: 4; -fx-padding: 8;");
        return card;
    }
}
