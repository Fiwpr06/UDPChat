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

        Scene scene = new Scene(root, 760, 720);
        
        URL commonCss = getClass().getResource("/com/udpchat/shared/styles/common.css");
        URL clientCss = getClass().getResource("/com/udpchat/client/styles/client.css");
        
        if (commonCss != null) scene.getStylesheets().add(commonCss.toExternalForm());
        if (clientCss != null) scene.getStylesheets().add(clientCss.toExternalForm());

        primaryStage.setTitle("UDP Client");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(720);
        primaryStage.setMinHeight(650);
        primaryStage.show();
    }

    @Override
    public void stop() throws Exception {
        if (controller != null && controller.getService() != null) {
            controller.getService().close();
        }
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
