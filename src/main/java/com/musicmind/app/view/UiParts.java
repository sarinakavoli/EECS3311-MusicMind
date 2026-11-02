package com.musicmind.app.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** Small building blocks the views share, so each view is only layout and event wiring. */
final class UiParts {

    static final double PAD = 12;
    static final double GAP = 8;
    private static final double HEADING_SIZE = 15;

    private UiParts() {
    }

    static Label heading(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, HEADING_SIZE));
        return label;
    }

    static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    static Label muted(String text) {
        Label label = wrapped(text);
        label.setStyle("-fx-text-fill: #666666;");
        return label;
    }

    /** A vertical page with the standard padding and gap. */
    static VBox page(Node... children) {
        VBox box = new VBox(GAP, children);
        box.setPadding(new Insets(PAD));
        return box;
    }

    /** A pill-shaped label: green for a met constraint, amber for one that is not. */
    static Label badge(ViewFormat.Badge badge) {
        Label label = new Label(badge.text());
        String colours = badge.warning()
                ? "-fx-background-color: #fff3cd; -fx-text-fill: #7a5a00;"
                : "-fx-background-color: #d9f2e0; -fx-text-fill: #1b5e20;";
        label.setStyle(colours + " -fx-padding: 2 10 2 10; -fx-background-radius: 10;");
        return label;
    }
}
