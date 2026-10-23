package com.udpchat.server.controller;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.*;
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
import java.util.*;
import java.util.stream.Collectors;

public class ServerController {
    @FXML private Label ipLabel;
    @FXML private Button copyIpButton;
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Region statusIndicator;

    // Database & OAuth Integration Status Controls
    @FXML private Region dbStatusIndicator;
    @FXML private Label dbStatusLabel;
    @FXML private Region oauthStatusIndicator;
    @FXML private Label oauthStatusLabel;
    @FXML private Button btnReloadOAuth;

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
    private RequestHandler requestHandler;
    private AuthService authService;
    private MailService mailService;
    private FileService fileService;
    private OAuthConfig oAuthConfig;
    private OAuthService oAuthService;
    private SmtpDeliveryService smtpDeliveryService;
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

        try {
            com.udpchat.server.database.DatabaseManager.initialize();
            String db = com.udpchat.server.database.DatabaseManager.getCurrentDb();
            if (dbStatusLabel != null) {
                dbStatusLabel.setText("Đã kết nối (" + db + ")");
            }
            if (dbStatusIndicator != null) {
                dbStatusIndicator.getStyleClass().removeAll("status-offline");
                dbStatusIndicator.getStyleClass().add("status-online");
            }
            log("[MySQL] Đã kết nối cơ sở dữ liệu '" + db + "' thành công.");
        } catch (Exception e) {
            if (dbStatusLabel != null) {
                dbStatusLabel.setText("Lỗi kết nối MySQL");
            }
            if (dbStatusIndicator != null) {
                dbStatusIndicator.getStyleClass().removeAll("status-online");
                dbStatusIndicator.getStyleClass().add("status-offline");
            }
            log("[MySQL] Cảnh báo kết nối CSDL: " + e.getMessage());
        }

        authService = new AuthService();
        mailService = new MailService(authService);
        fileService = new FileService();

        loadOAuthConfig();

        updateTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            updateOnlineClients();
            updateServerStatistics();
        }));
        updateTimeline.setCycleCount(Timeline.INDEFINITE);

        updateServerStatistics();
    }

    private void loadOAuthConfig() {
        oAuthConfig = OAuthConfig.load();
        oAuthService = new OAuthService(oAuthConfig, authService);
        smtpDeliveryService = new SmtpDeliveryService(oAuthService, this::log);

        if (oAuthConfig.isConfigured()) {
            if (oauthStatusLabel != null) {
                oauthStatusLabel.setText("Sẵn sàng (" + maskClientId(oAuthConfig.getClientId()) + ")");
            }
            if (oauthStatusIndicator != null) {
                oauthStatusIndicator.getStyleClass().removeAll("status-offline");
                oauthStatusIndicator.getStyleClass().add("status-online");
            }
            log("[OAuth 2.0] Đã nạp thành công cấu hình Google Client.");
        } else {
            if (oauthStatusLabel != null) {
                oauthStatusLabel.setText("Chưa cấu hình (client_secret.json)");
            }
            if (oauthStatusIndicator != null) {
                oauthStatusIndicator.getStyleClass().removeAll("status-online");
                oauthStatusIndicator.getStyleClass().add("status-offline");
            }
            log("[OAuth 2.0] Lưu ý: Hãy cấu hình client_secret.json nếu muốn đăng nhập Google qua trình duyệt.");
        }
    }

    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 12) return "Desktop App";
        return clientId.substring(0, 8) + "...";
    }

    @FXML
    private void onReloadOAuth() {
        loadOAuthConfig();
        if (requestHandler != null) {
            requestHandler = new RequestHandler(authService, mailService, fileService, oAuthService, smtpDeliveryService, this::log);
        }
        log("[OAuth 2.0] Đã tải lại cấu hình Google OAuth.");
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

        requestHandler = new RequestHandler(authService, mailService, fileService, oAuthService, smtpDeliveryService, this::log);
        network = new ServerNetwork(requestHandler, this::log);

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
        if (smtpDeliveryService != null) {
            smtpDeliveryService.shutdown();
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
