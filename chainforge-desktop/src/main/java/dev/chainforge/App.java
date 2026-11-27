package dev.chainforge;

import dev.chainforge.data.Database;
import dev.chainforge.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {
    public void start(Stage stage) throws IOException {
        Database.initialize();
        Scene scene = new Scene(new MainView().build(), 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setTitle("ChainForge • Blockchain Test Studio");
        stage.setMinWidth(1120);
        stage.setMinHeight(720);
        stage.setScene(scene);

        try (var iconStream = getClass().getResourceAsStream("/images/chainforge.png")) {
            if (iconStream != null) {
                stage.getIcons().add(new javafx.scene.image.Image(iconStream));
            }
        }
        stage.setTitle("ChainForge Desktop");
        stage.setFullScreen(true);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}