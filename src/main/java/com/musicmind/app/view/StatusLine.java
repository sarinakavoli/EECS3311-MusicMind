package com.musicmind.app.view;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;

/** A line under a view's controls: a spinner while work runs, then a message or an error. */
public final class StatusLine extends HBox {

    private static final String ERROR_CLASS = "mm-status-error";
    private static final double SPINNER_SIZE = 18;

    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label text = new Label();

    public StatusLine() {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        spinner.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        spinner.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        spinner.setVisible(false);
        spinner.setManaged(false);
        text.setWrapText(true);
        getChildren().addAll(spinner, text);
        // An empty line takes no room, so it leaves no gap between the controls and what follows.
        visibleProperty().bind(text.textProperty().isNotEmpty().or(spinner.visibleProperty()));
        managedProperty().bind(visibleProperty());
    }

    /** Work has started; shows the spinner and what is happening. */
    public void busy(String message) {
        spinner.setVisible(true);
        spinner.setManaged(true);
        text.getStyleClass().remove(ERROR_CLASS);
        text.setText(message);
    }

    /** Work is over; shows a plain message (or nothing for an empty one). */
    public void info(String message) {
        hideSpinner();
        text.getStyleClass().remove(ERROR_CLASS);
        text.setText(message == null ? "" : message);
    }

    /** Work failed; shows why, in red. */
    public void error(String message) {
        hideSpinner();
        text.getStyleClass().remove(ERROR_CLASS);
        text.getStyleClass().add(ERROR_CLASS);
        text.setText(message);
    }

    private void hideSpinner() {
        spinner.setVisible(false);
        spinner.setManaged(false);
    }
}
