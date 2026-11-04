package com.musicmind.app.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;

/**
 * The one in-app question the GUI asks: a dark MusicMind surface with the title in white, a message (or any
 * content), and buttons. No stock icon and no system title bar. A quiet button keeps things as they are; the
 * main button is green, or red when the action destroys something. Escape and closing answer "no choice".
 */
final class ConfirmDialog {

    /** How a button looks: SECONDARY is the quiet one, PRIMARY the green main action, DANGER a red destructive one. */
    enum Kind { SECONDARY, PRIMARY, DANGER }

    record Choice(String label, Kind kind) {
        static Choice secondary(String label) {
            return new Choice(label, Kind.SECONDARY);
        }

        static Choice primary(String label) {
            return new Choice(label, Kind.PRIMARY);
        }

        static Choice danger(String label) {
            return new Choice(label, Kind.DANGER);
        }
    }

    static final int NONE = -1;
    private static final double WIDTH = 460;

    private ConfirmDialog() {
    }

    /** A yes/no question: true only if the main button was pressed. */
    static boolean confirm(Window owner, String title, String message, String keepLabel, String doLabel, boolean destructive) {
        Label text = UiParts.wrapped(message);
        text.getStyleClass().add("mm-confirm-message");
        int picked = show(owner, title, text, List.of(Choice.secondary(keepLabel),
                destructive ? Choice.danger(doLabel) : Choice.primary(doLabel)));
        return picked == 1;
    }

    /**
     * Shows the dialog and waits (JavaFX thread only).
     *
     * @return the index of the pressed choice, or {@link #NONE} when it was closed with Escape
     */
    static int show(Window owner, String title, Node body, List<Choice> choices) {
        Window parent = owner != null ? owner : focusedWindow();
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        if (parent != null) {
            stage.initOwner(parent);
        }
        int[] answer = {NONE};

        Label heading = new Label(title);
        heading.getStyleClass().add("mm-confirm-title");
        heading.setWrapText(true);
        // Two buttons sit side by side on the right; more (the choices of a conflict) stack, one per line.
        boolean stacked = choices.size() > 2;
        Pane buttons = stacked ? new VBox(UiParts.GAP) : new HBox(UiParts.GAP);
        buttons.getStyleClass().add("mm-confirm-buttons");
        if (buttons instanceof HBox row) {
            row.setAlignment(Pos.CENTER_RIGHT);
        }
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            Button button = new Button(choice.label());
            button.setMinWidth(Region.USE_PREF_SIZE);
            if (stacked) {
                button.setMaxWidth(Double.MAX_VALUE);
            }
            switch (choice.kind()) {
                case PRIMARY -> {
                    button.getStyleClass().add("mm-primary");
                }
                case DANGER -> button.getStyleClass().add("mm-danger-solid");
                default -> {
                }
            }
            int index = i;
            button.setOnAction(e -> {
                answer[0] = index;
                stage.close();
            });
            buttons.getChildren().add(button);
        }
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox root = new VBox(UiParts.GAP + 6, heading, body, buttons);
        root.getStyleClass().add("mm-confirm");
        root.setPrefWidth(WIDTH);
        root.setMaxHeight(UiParts.fitHeight(Double.MAX_VALUE, Screen.getPrimary().getVisualBounds().getHeight()));

        Scene scene = new Scene(root);
        UiParts.dark(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.showAndWait();
        return answer[0];
    }

    /** The window the user is working in, so a question raised from a worker callback still opens over it. */
    private static Window focusedWindow() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(Window::isFocused).findFirst()
                .orElseGet(() -> Window.getWindows().stream().filter(Window::isShowing).findFirst().orElse(null));
    }
}
