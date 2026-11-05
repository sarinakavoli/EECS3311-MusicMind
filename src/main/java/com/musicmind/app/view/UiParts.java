package com.musicmind.app.view;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Small building blocks the views share, so each view is only layout and event wiring. */
public final class UiParts {

    /**
     * The spacing scale, in pixels. Every gap and padding in the views is one of these (the style sheet uses the
     * same numbers): {@code SPACE_XS} only between a label and its caption (a title over its artist), {@code SPACE_S}
     * between items in a group, {@code SPACE_M} inside a card or section, {@code SPACE_L} between the sections of a
     * page, {@code SPACE_XL} around the biggest blocks.
     */
    public static final double SPACE_XS = 4;
    public static final double SPACE_S = 8;
    public static final double SPACE_M = 16;
    public static final double SPACE_L = 24;
    public static final double SPACE_XL = 32;

    private UiParts() {
    }

    static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mm-heading");
        return label;
    }

    /** A table or group header: bold like a heading but smaller, so a card or section title stays the biggest. */
    static Label subhead(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mm-subhead");
        return label;
    }

    static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    /** Items that belong together (a control row and the status line under it): {@code SPACE_S} apart. */
    static VBox group(Node... children) {
        return new VBox(SPACE_S, children);
    }

    /** Hides a label (and the page gap it would leave) while its text is empty. */
    static Label collapsing(Label label) {
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }

    /** Hides a box (and the page gap it would leave) while it holds nothing. */
    static <T extends javafx.scene.layout.Pane> T collapsing(T box) {
        box.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(box.getChildren()));
        box.managedProperty().bind(box.visibleProperty());
        return box;
    }

    static Label muted(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("mm-muted");
        return label;
    }

    /** A helper line under a control: small (12px) and muted. */
    static Label caption(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("mm-caption");
        return label;
    }

    /** A check box with its helper line {@code SPACE_XS} under it, indented to line up with the box's label. */
    static VBox checkWithCaption(javafx.scene.control.CheckBox box, Label caption) {
        caption.getStyleClass().add("mm-check-caption");
        return new VBox(SPACE_XS, box, caption);
    }

    /**
     * A screen's page: the large title, a one-line description, an optional "Try: ..." example (blank for none),
     * then the children. The look comes from the {@code mm-page*} style classes.
     */
    static VBox page(String title, String description, String example, Node... children) {
        return page(title, description, example == null || example.isBlank() ? null : example(example), children);
    }

    /**
     * The same with the example as a label the screen can hide: the "Try: ..." line only helps while the screen has
     * no content yet (see {@link #show}).
     */
    static VBox page(String title, String description, Label example, Node... children) {
        VBox box = new VBox(SPACE_L);
        box.getStyleClass().add("mm-page");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("mm-page-title");
        VBox header = new VBox(SPACE_XS, titleLabel);   // the title and the lines that explain it stay one block
        if (description != null && !description.isBlank()) {
            header.getChildren().add(styled(description, "mm-page-desc"));
        }
        if (example != null) {
            header.getChildren().add(example);
        }
        box.getChildren().add(header);
        box.getChildren().addAll(children);
        return box;
    }

    /** A "Try: ..." line for {@link #page(String, String, Label, Node...)}. */
    static Label example(String text) {
        return styled(text, "mm-example");
    }

    /** Shows or hides a node, and the room it takes. */
    static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
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

    private static final String STYLESHEET = "/com/musicmind/app/musicmind.css";

    /** Gives a scene of its own (an in-app dialog) the dark stylesheet. */
    static void dark(javafx.scene.Scene scene) {
        java.net.URL sheet = UiParts.class.getResource(STYLESHEET);
        if (sheet != null && !scene.getStylesheets().contains(sheet.toExternalForm())) {
            scene.getStylesheets().add(sheet.toExternalForm());
        }
    }

    /** Gives a dialog the dark look even when it has no owner window to inherit the stylesheet from. */
    static void dark(javafx.scene.control.DialogPane pane) {
        java.net.URL sheet = UiParts.class.getResource(STYLESHEET);
        if (sheet != null && !pane.getStylesheets().contains(sheet.toExternalForm())) {
            pane.getStylesheets().add(sheet.toExternalForm());
        }
    }

    private static final double SCREEN_SHARE = 0.9;

    /** A dialog's height: the preferred one, but never more than 90% of the screen. */
    static double fitHeight(double preferred, double screenHeight) {
        return Math.min(preferred, screenHeight * SCREEN_SHARE);
    }
}
