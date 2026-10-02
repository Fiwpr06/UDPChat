package com.udpchat.server;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ServerApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/udpchat/server/server_view.fxml"));
        Scene scene = new Scene(loader.load(), 900, 680);
        
        try {
            scene.getStylesheets().add(getClass().getResource("/com/udpchat/shared/styles/common.css").toExternalForm());
        } catch(Exception e){}
        try {
            scene.getStylesheets().add(getClass().getResource("/com/udpchat/server/styles/server.css").toExternalForm());
        } catch(Exception e){}

        primaryStage.setTitle("UDP Mail Server");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(850);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    @Override
    public void stop() {
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
