package com.udpchat.server.controller;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MessageService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

public class ServerController {
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Label onlineCountLabel;
    @FXML private Region statusIndicator;
    @FXML private TextArea logArea;
    @FXML private ListView<String> clientListView;

    private ServerNetwork network;
    private AuthService authService;
    private MessageService messageService;
    private FileService fileService;
    private Thread serverThread;
    private Timeline updateTimeline;

    @FXML
    public void initialize() {
        portField.setText("8888");
        stopButton.setDisable(true);
        statusIndicator.getStyleClass().add("status-offline");
        
        authService = new AuthService();
        messageService = new MessageService();
        fileService = new FileService();
        
        updateTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> updateOnlineClients()));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    @FXML
    private void onStart() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            log("Invalid port number");
            return;
        }

        RequestHandler handler = new RequestHandler(authService, messageService, fileService, this::log);
        network = new ServerNetwork(handler, this::log);
        
        serverThread = new Thread(() -> network.start(port));
        serverThread.setDaemon(true);
        serverThread.start();

        startButton.setDisable(true);
        stopButton.setDisable(false);
        portField.setDisable(true);
        statusLabel.setText("Running");
        statusIndicator.getStyleClass().remove("status-offline");
        statusIndicator.getStyleClass().add("status-online");
        updateTimeline.play();
    }

    @FXML
    private void onStop() {
        if (network != null) {
            network.stop();
        }
        updateTimeline.stop();
        
        startButton.setDisable(false);
        stopButton.setDisable(true);
        portField.setDisable(false);
        statusLabel.setText("Stopped");
        statusIndicator.getStyleClass().remove("status-online");
        statusIndicator.getStyleClass().add("status-offline");
    }

    private void log(String msg) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            logArea.appendText("[" + time + "] " + msg + "\n");
        });
    }

    private void updateOnlineClients() {
        if (authService != null) {
            int count = authService.getOnlineCount();
            Platform.runLater(() -> {
                onlineCountLabel.setText("Online: " + count);
                clientListView.getItems().setAll(
                    authService.getOnlineSessions().stream()
                        .map(ClientSession::toString)
                        .collect(Collectors.toList())
                );
            });
        }
    }
}
