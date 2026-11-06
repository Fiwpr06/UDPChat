package com.udpchat.server;

import com.udpchat.server.controller.ServerController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ServerApp extends Application {
    private ServerController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/udpchat/server/server_view.fxml"));
        Scene scene = new Scene(loader.load(), 950, 700);
        controller = loader.getController();

        try {
            scene.getStylesheets().add(getClass().getResource("/com/udpchat/shared/styles/common.css").toExternalForm());
        } catch (Exception ignored) {}

        primaryStage.setTitle("Mail Server (SMTP 2525 & POP3 1110) — Lab 3");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
