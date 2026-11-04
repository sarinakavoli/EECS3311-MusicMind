package com.musicmind.app.view;

import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Relaxation;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** F11: states what cannot be met and offers the ways to adjust the request; cancelling picks none. */
final class NegotiationDialog {

    private NegotiationDialog() {
    }

    /**
     * Shows the conflicts and one button per option. Call on the JavaFX thread.
     *
     * @param options the report's relaxations worded for the user, in the same order
     * @return the chosen relaxation, or empty if the user cancelled
     */
    static Optional<Relaxation> ask(ViewFormat format, FeasibilityReport report, List<String> options) {
        VBox body = new VBox(UiParts.GAP);
        for (String conflict : report.conflicts()) {
            Label line = UiParts.wrapped(conflict);
            line.getStyleClass().add("mm-confirm-message");
            body.getChildren().add(line);
        }
        body.getChildren().add(UiParts.muted(format.w("ConflictChoose")));
        List<ConfirmDialog.Choice> choices = new ArrayList<>();
        options.forEach(option -> choices.add(ConfirmDialog.Choice.primary(option)));
        choices.add(ConfirmDialog.Choice.secondary(format.w("ConflictCancel")));   // last: the way out

        int picked = ConfirmDialog.show(null, format.w("ConflictTitle"), body, choices);
        return picked >= 0 && picked < options.size() && picked < report.relaxations().size() ? Optional.of(report.relaxations().get(picked)) : Optional.empty();
    }
}
