package dev.chainforge;

import javafx.application.Application;

/**
 * Non-modular JavaFX entry point for packaged distributions.
 * Keep this class separate from the JavaFX Application subclass.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
