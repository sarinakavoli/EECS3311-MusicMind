package com.musicmind.app.view;

import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * F2: the one-sentence summary first, then one table of the top genres and artists across the time ranges
 * (oldest to newest, a tick where the item is in that range) with a Change column saying what is new or dropped.
 */
public final class TrendsView implements TabView {

    private final GuiContext ctx;
    private final ViewFormat format;
    private final Button compare;
    private final StatusLine status = new StatusLine();
    private final Label summary = UiParts.collapsing(UiParts.wrapped(""));
    private final GridPane table = new GridPane();
    private final Label coverage = UiParts.collapsing(UiParts.caption(""));
    private final VBox tableCard;
    private final VBox notices = UiParts.collapsing(new VBox(UiParts.SPACE_S));
    private final Node root;

    public TrendsView(GuiContext ctx) {
        this.ctx = ctx;
        this.format = ctx.format();
        compare = new Button(format.w("CompareTrends"));
        compare.getStyleClass().add("mm-primary");
        compare.setDefaultButton(true);
        compare.setOnAction(e -> run());
        summary.getStyleClass().add("mm-summary");
        table.setHgap(UiParts.SPACE_M);
        table.setVgap(UiParts.SPACE_S);
        tableCard = buildTableCard();
        HBox controls = new HBox(UiParts.SPACE_S, compare);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox content = UiParts.page(format.w("Menu.trends"), format.w("Desc.trends"), "",
                UiParts.group(controls, status), summary, notices, tableCard);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root = new LoginGate(ctx, scroll, false);
        status.info(format.w("TrendsHint"));
    }

    private VBox buildTableCard() {
        VBox card = new VBox(UiParts.SPACE_M, table, UiParts.group(coverage, UiParts.caption(format.w("TrendsExplain"))));
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
        if (!tableCard.isVisible()) {   // the hint is stale once a comparison is on screen
            status.info(format.w("TrendsHint"));
        }
    }

    private void run() {
        User user = ctx.userProperty().get();
        ctx.run(status, "ActionCompare", format.w("TrendsBuilding"),
                () -> ctx.controller().analyzeTrends(user), this::show, compare);
    }

    private void show(TrendReport report) {
        summary.setText(report.narrative() == null ? "" : report.narrative());
        notices.getChildren().setAll(report.gaps().stream().map(UiParts::wrapped).toList());
        ViewFormat.TrendTable data = format.trendTable(report, ctx.config().profileRanges(),
                ctx.config().guiArtistsShown(), ctx.config().guiTrendGenresShown());
        fill(data);
        coverage.setText(data.coverage());
        tableCard.setVisible(!data.rows().isEmpty());
        tableCard.setManaged(tableCard.isVisible());
    }

    /** Header row (the periods and Change), then each row: a section header spans the table, an item gets ticks. */
    private void fill(ViewFormat.TrendTable data) {
        table.getChildren().clear();
        table.getColumnConstraints().clear();
        int periods = data.periods().size();
        ColumnConstraints label = new ColumnConstraints();
        label.setHgrow(Priority.ALWAYS);
        label.setMinWidth(120);
        table.getColumnConstraints().add(label);
        for (int c = 0; c <= periods; c++) {   // the periods, then Change
            ColumnConstraints col = new ColumnConstraints();
            col.setHalignment(c < periods ? HPos.CENTER : HPos.LEFT);
            table.getColumnConstraints().add(col);
        }
        for (int c = 0; c < periods; c++) {
            table.add(UiParts.subhead(data.periods().get(c)), c + 1, 0);
        }
        table.add(UiParts.subhead(format.w("TrendChange")), periods + 1, 0);
        int r = 1;
        for (ViewFormat.TrendRow row : data.rows()) {
            if (row.header()) {
                Label head = UiParts.subhead(row.label());
                GridPane.setMargin(head, new Insets(UiParts.SPACE_S, 0, 0, 0));
                table.add(head, 0, r, periods + 2, 1);
            } else {
                table.add(new Label(row.label()), 0, r);
                for (int c = 0; c < periods; c++) {
                    if (row.present().get(c)) {
                        Label tick = new Label(format.w("TrendPresent"));
                        tick.setAccessibleText(row.label() + ", " + data.periods().get(c));
                        table.add(tick, c + 1, r);
                    }
                }
                if (row.change() != ViewFormat.TrendChange.NONE) {
                    Label change = new Label(row.changeText());
                    change.getStyleClass().add(row.change() == ViewFormat.TrendChange.NEW ? "mm-trend-new" : "mm-trend-dropped");
                    table.add(change, periods + 1, r);
                }
            }
            r++;
        }
    }
}
