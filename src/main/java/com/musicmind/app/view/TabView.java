package com.musicmind.app.view;

import com.musicmind.domain.User;
import javafx.scene.Node;

/** A screen of the main window, chosen from the left menu. */
public interface TabView {

    /** The screen's title, used for the menu button and the page title. */
    String title();

    /** One plain sentence under the page title saying what the screen does. */
    String description();

    /** A "Try: ..." line for screens that take input; empty when the screen has none. */
    default String example() {
        return "";
    }

    /** The tab's content, already wrapped so that it is unusable until the user can use it. */
    Node node();

    /** Called on the JavaFX thread after a successful login. */
    void onLogin(User user);

    /** Called on the JavaFX thread each time the screen is opened from the menu. */
    default void onShown() {
    }
}
