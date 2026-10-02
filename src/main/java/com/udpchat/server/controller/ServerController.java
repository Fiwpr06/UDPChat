package com.udpchat.server.controller;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MailService;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

public class ServerController {
    @FXML private Label ipLabel;
    @FXML private Button copyIpButton;
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Region statusIndicator;

    @FXML private Label totalEmailsLabel;
    @FXML private Label todayEmailsLabel;
    @FXML private Label attachmentsCountLabel;
    @FXML private Label storageSizeLabel;

    @FXML private Label onlineCountLabel;
    @FXML private Button copyClientIpButton;
    @FXML private ListView<String> clientListView;

    @FXML private VBox logContainer;
    @FXML private TextArea logArea;
    @FXML private Button toggleLogButton;
    @FXML private Button clearLogButton;

    private ServerNetwork network;
    private AuthService authService;
    private MailService mailService;
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
        mailService = new MailService();
        fileService = new FileService();

        updateTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            updateOnlineClients();
            updateServerStatistics();
        }));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);

        updateServerStatistics();
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

        RequestHandler handler = new RequestHandler(authService, mailService, fileService, this::log);
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
            log("Vui lòng chọn một người dùng trong danh sách trước khi sao chép IP.");
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
                onlineCountLabel.setText("Người dùng đang online: " + count);
                clientListView.getItems().setAll(
                        authService.getOnlineSessions().stream()
                                .map(ClientSession::toString)
                                .collect(Collectors.toList())
                );
            });
        }
    }

    private void updateServerStatistics() {
        if (mailService != null && fileService != null) {
            Map<String, Object> stats = mailService.getServerStats();
            long total = ((Number) stats.getOrDefault("totalEmails", 0)).longValue();
            long today = ((Number) stats.getOrDefault("todayEmails", 0)).longValue();
            int attachCount = fileService.getAttachmentCount();
            long attachBytes = fileService.getTotalAttachmentsSize();

            String formattedSize;
            if (attachBytes < 1024) formattedSize = attachBytes + " B";
            else if (attachBytes < 1024 * 1024) formattedSize = String.format("%.1f KB", attachBytes / 1024.0);
            else formattedSize = String.format("%.2f MB", attachBytes / (1024.0 * 1024.0));

            Platform.runLater(() -> {
                totalEmailsLabel.setText(String.valueOf(total));
                todayEmailsLabel.setText(String.valueOf(today));
                attachmentsCountLabel.setText(String.valueOf(attachCount));
                storageSizeLabel.setText(formattedSize);
            });
        }
    }
}
