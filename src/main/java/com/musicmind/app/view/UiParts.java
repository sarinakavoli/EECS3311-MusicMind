package com.musicmind.app.view;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Small building blocks the views share, so each view is only layout and event wiring. */
final class UiParts {

    static final double PAD = 12;
    static final double GAP = 8;

    private UiParts() {
    }

    static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mm-heading");
        return label;
    }

    static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    static Label muted(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("mm-muted");
        return label;
    }

    /**
     * A screen's page: the large title, a one-line description, an optional "Try: ..." example (blank for none),
     * then the children. The look comes from the {@code mm-page*} style classes.
     */
    static VBox page(String title, String description, String example, Node... children) {
        VBox box = new VBox(GAP);
        box.getStyleClass().add("mm-page");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("mm-page-title");
        box.getChildren().add(titleLabel);
        if (description != null && !description.isBlank()) {
            box.getChildren().add(styled(description, "mm-page-desc"));
        }
        if (example != null && !example.isBlank()) {
            box.getChildren().add(styled(example, "mm-example"));
        }
        box.getChildren().addAll(children);
        return box;
    }

    private static Label styled(String text, String styleClass) {
        Label label = wrapped(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /** A pill-shaped label: green for a met constraint, amber for one that is not. */
    static Label badge(ViewFormat.Badge badge) {
        Label label = new Label(badge.text());
        label.getStyleClass().add(badge.warning() ? "mm-badge-warn" : "mm-badge");
        label.getStyleClass().add("mm-badge");
        return label;
    }
}
