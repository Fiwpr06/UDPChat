package com.udpchat.server.controller;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MessageService;
import com.udpchat.shared.util.UDPUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public class ServerController {
    @FXML private Label ipLabel;
    @FXML private Button copyIpButton;
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Label onlineCountLabel;
    @FXML private Region statusIndicator;
    @FXML private Button copyClientIpButton;
    @FXML private ListView<String> clientListView;
    @FXML private VBox logContainer;
    @FXML private TextArea logArea;
    @FXML private Button toggleLogButton;
    @FXML private Button clearLogButton;
    @FXML private Button openLogFileButton;

    private ServerNetwork network;
    private AuthService authService;
    private MessageService messageService;
    private FileService fileService;
    private Thread serverThread;
    private Timeline updateTimeline;

    @FXML
    public void initialize() {
        String localIp = UDPUtil.getLocalIPAddress();
        ipLabel.setText(localIp);
        portField.setText("8888");
        stopButton.setDisable(true);
        statusLabel.setText("Đã dừng");
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
            log("Lỗi: Số cổng (port) không hợp lệ");
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
        statusLabel.setText("Đang chạy");
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
        statusLabel.setText("Đã dừng");
        statusIndicator.getStyleClass().remove("status-online");
        statusIndicator.getStyleClass().add("status-offline");
    }

    @FXML
    private void onCopyIp() {
        String ip = ipLabel.getText().trim();
        copyToClipboard(ip, copyIpButton, "📋 Copy IP");
    }

    @FXML
    private void onCopyClientIp() {
        String selected = clientListView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isEmpty()) {
            log("Vui lòng chọn một client trong danh sách trước khi sao chép IP.");
            return;
        }
        
        String ip = selected;
        int start = selected.indexOf('(');
        int colon = selected.indexOf(':');
        if (start != -1 && colon != -1 && colon > start) {
            ip = selected.substring(start + 1, colon).trim();
        }
        copyToClipboard(ip, copyClientIpButton, "📋 Copy IP Client chọn");
    }

    @FXML
    private void onToggleLog() {
        boolean isVisible = logArea.isVisible();
        logArea.setVisible(!isVisible);
        logArea.setManaged(!isVisible);
        toggleLogButton.setText(!isVisible ? "👁️ Ẩn Log" : "👁️ Hiện Log");
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
        log("Đã xóa nhật ký hiển thị trên màn hình.");
    }

    @FXML
    private void onOpenLogFile() {
        File logFile = new File("server-data/messages/messages.log");
        if (logFile.exists()) {
            try {
                new ProcessBuilder("notepad.exe", logFile.getAbsolutePath()).start();
                log("Đã mở file nhật ký tin nhắn bằng Notepad: " + logFile.getAbsolutePath());
            } catch (Exception e) {
                log("Không thể mở Notepad: " + e.getMessage() + ". File tại: " + logFile.getAbsolutePath());
            }
        } else {
            log("Chưa có dữ liệu tin nhắn (file messages.log chưa được tạo).");
        }
    }

    private void copyToClipboard(String text, Button btn, String originalText) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);

        btn.setText("✓ Đã copy!");
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> btn.setText(originalText));
        }).start();
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
                onlineCountLabel.setText("Đang online: " + count);
                clientListView.getItems().setAll(
                    authService.getOnlineSessions().stream()
                        .map(ClientSession::toString)
                        .collect(Collectors.toList())
                );
            });
        }
    }
}
