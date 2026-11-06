package com.udpchat.client;

import com.udpchat.client.controller.ClientController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class ClientApp extends Application {
    private ClientController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/udpchat/client/client_view.fxml"));
        Parent root = loader.load();
        controller = loader.getController();

        Scene scene = new Scene(root, 1020, 720);

        URL commonCss = getClass().getResource("/com/udpchat/shared/styles/common.css");
        if (commonCss != null) scene.getStylesheets().add(commonCss.toExternalForm());

        primaryStage.setTitle("Mail Client (SMTP 2525 & POP3 1110) — Lab 3");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(650);
        primaryStage.show();
    }

    @Override
    public void stop() throws Exception {
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
