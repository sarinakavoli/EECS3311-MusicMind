package com.musicmind.app.view;

import com.musicmind.domain.User;
import javafx.scene.Node;

/** A tab of the main window. */
public interface TabView {

    /** The tab's title. */
    String title();

    /** The tab's content, already wrapped so that it is unusable until the user can use it. */
    Node node();

    /** Called on the JavaFX thread after a successful login. */
    void onLogin(User user);
}
