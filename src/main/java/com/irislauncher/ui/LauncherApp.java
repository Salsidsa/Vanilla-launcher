package com.irislauncher.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;

public class LauncherApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        URL fxml = getClass().getResource("/com/irislauncher/launcher.fxml");
        if (fxml == null) throw new RuntimeException("launcher.fxml not found");

        FXMLLoader loader = new FXMLLoader(fxml);
        Scene scene = new Scene(loader.load(), 780, 360);
        // inline style is set in FXML; no external CSS needed

        stage.setTitle("Vanilla Launcher");
        stage.setScene(scene);
        stage.setResizable(false);

        // Set window icon (PNG only - JavaFX does not support .ico)
        for (String name : new String[]{"icon.png", "icon-128.png", "icon-64.png"}) {
            try {
                URL iconUrl = getClass().getResource("/com/irislauncher/" + name);
                if (iconUrl != null) {
                    stage.getIcons().add(new Image(iconUrl.toExternalForm()));
                    break;
                }
            } catch (Exception ignored) {}
        }

        stage.show();
    }
}
