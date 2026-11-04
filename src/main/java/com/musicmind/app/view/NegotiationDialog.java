package com.musicmind.app.view;

import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Relaxation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** F11: states what cannot be met and offers the ways to adjust the request; cancelling picks none. */
final class NegotiationDialog {

    private static final double WIDTH = 460;

    private NegotiationDialog() {
    }

    /**
     * Shows the conflicts and one button per option. Call on the JavaFX thread.
     *
     * @param options the report's relaxations worded for the user, in the same order
     * @return the chosen relaxation, or empty if the user cancelled
     */
    static Optional<Relaxation> ask(ViewFormat format, FeasibilityReport report, List<String> options) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle(format.w("ConflictTitle"));
        alert.setHeaderText(format.w("ConflictTitle"));
        VBox body = new VBox(UiParts.GAP);
        body.setPrefWidth(WIDTH);
        for (String conflict : report.conflicts()) {
            Label line = UiParts.wrapped(conflict);
            line.setMaxWidth(WIDTH);
            body.getChildren().add(line);
        }
        body.getChildren().add(UiParts.muted(format.w("ConflictChoose")));
        alert.getDialogPane().setContent(body);
        UiParts.dark(alert.getDialogPane());

        List<ButtonType> optionButtons = new ArrayList<>();
        for (String option : options) {
            optionButtons.add(new ButtonType(option, ButtonBar.ButtonData.OTHER));
        }
        ButtonType cancel = new ButtonType(format.w("ConflictCancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(optionButtons);
        alert.getButtonTypes().add(cancel);

        Optional<ButtonType> picked = alert.showAndWait();
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        int index = optionButtons.indexOf(picked.get());
        return index >= 0 && index < report.relaxations().size() ? Optional.of(report.relaxations().get(index)) : Optional.empty();
    }
}
