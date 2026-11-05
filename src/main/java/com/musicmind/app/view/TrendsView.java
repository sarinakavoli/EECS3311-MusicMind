package com.musicmind.app.view;

import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** F2: genres and artists side by side across the time ranges, what changed between them, and a short narrative. */
public final class TrendsView implements TabView {

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Button compare;
    private final StatusLine status = new StatusLine();
    private final HBox columns = UiParts.collapsing(new HBox(UiParts.SPACE_L));
    private final VBox changes = new VBox(UiParts.SPACE_S);
    private final VBox changesCard;
    private final Label narrative = UiParts.collapsing(UiParts.wrapped(""));
    private final VBox notices = UiParts.collapsing(new VBox(UiParts.SPACE_S));
    private final Node root;

    public TrendsView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        compare = new Button(format.w("CompareTrends"));
        compare.getStyleClass().add("mm-primary");
        compare.setDefaultButton(true);
        compare.setOnAction(e -> run());
        changesCard = buildChangesCard();
        HBox controls = new HBox(UiParts.SPACE_S, compare);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox content = UiParts.page(format.w("Menu.trends"), format.w("Desc.trends"), "", UiParts.group(controls, status), columns, notices, changesCard, narrative);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
        status.info(format.w("TrendsHint"));
    }

    private VBox buildChangesCard() {
        VBox card = new VBox(UiParts.SPACE_S, UiParts.heading(format.w("TrendsOverTime")), UiParts.muted(format.w("TrendsExplain")), changes);
        card.getStyleClass().add("mm-card");
        card.setVisible(false);
        card.setManaged(false);
        return card;
    }

    @Override
    public String title() {
        return format.w("Menu.trends");
    }

    @Override
    public String description() {
        return format.w("Desc.trends");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onLogin(User user) {
        if (columns.getChildren().isEmpty()) {   // the hint is stale once a comparison is on screen
            status.info(format.w("TrendsHint"));
        }
    }

    private void run() {
        User user = ctx.userProperty().get();
        ctx.run(status, "ActionCompare", format.w("TrendsBuilding"),
                () -> ctx.controller().analyzeTrends(user), this::show, compare);
    }

    private void show(TrendReport report) {
        columns.getChildren().clear();
        List<String> ranges = ctx.config().profileRanges();
        for (ViewFormat.TrendColumn column : format.trendColumns(report, ranges, ctx.config().guiArtistsShown(),
                ctx.config().guiTrendGenresShown())) {
            VBox box = new VBox(UiParts.SPACE_S, UiParts.heading(column.range()),
                    UiParts.heading(format.w("Genres")),
                    list(column.genres()), UiParts.heading(format.w("Artists")),
                    list(column.artists()));
            if (!column.coverage().isEmpty()) {
                box.getChildren().add(UiParts.muted(column.coverage()));
            }
            box.getStyleClass().add("mm-card");
            HBox.setHgrow(box, Priority.ALWAYS);
            columns.getChildren().add(box);
        }
        changesCard.setVisible(true);
        changesCard.setManaged(true);
        changes.getChildren().setAll(format.trendStepLines(report).stream().map(UiParts::wrapped).toList());
        notices.getChildren().setAll(report.gaps().stream().map(UiParts::wrapped).toList());
        narrative.setText(report.narrative() == null ? "" : report.narrative());
    }

    private static VBox list(List<String> items) {
        VBox box = new VBox(UiParts.SPACE_S);
        items.forEach(item -> box.getChildren().add(new Label(item)));
        return box;
    }
}
