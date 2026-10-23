package com.udpchat.server.controller;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.GmailConfig;
import com.udpchat.server.service.GmailGateway;
import com.udpchat.server.service.MailService;
import com.udpchat.shared.model.Email;
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

    // Gmail Gateway Relay Controls
    @FXML private Region gmailStatusIndicator;
    @FXML private Label gmailStatusLabel;
    @FXML private TextField gmailAddressField;
    @FXML private PasswordField gmailPasswordField;
    @FXML private Button btnVerifyGmail;
    @FXML private Button btnToggleGmail;
    @FXML private Button btnSyncGmail;

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
    private GmailConfig gmailConfig;
    private GmailGateway gmailGateway;
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
            log("[MySQL] Đã kết nối cơ sở dữ liệu '" + com.udpchat.server.database.DatabaseManager.getCurrentDb() + "' thành công.");
        } catch (Exception e) {
            log("[MySQL] Cảnh báo kết nối CSDL: " + e.getMessage());
        }

        authService = new AuthService();
        mailService = new MailService(authService);
        fileService = new FileService();

        gmailConfig = new GmailConfig();
        gmailAddressField.setText(gmailConfig.getGmailAddress());
        gmailPasswordField.setText(gmailConfig.getAppPassword());
        updateGmailUiStatus(gmailConfig.isEnabled() && gmailConfig.isConfigured());

        com.udpchat.server.service.OAuthConfig oAuthConfig = com.udpchat.server.service.OAuthConfig.load();
        com.udpchat.server.service.OAuthService oAuthService = new com.udpchat.server.service.OAuthService(oAuthConfig, authService);
        if (oAuthConfig.isConfigured()) {
            log("[OAuth 2.0] Đã nạp thành công Google Client ID.");
        } else {
            log("[OAuth 2.0] Lưu ý: Hãy cấu hình client_secret.json để bật đăng nhập Gmail qua browser.");
        }

        gmailGateway = new GmailGateway(gmailConfig, oAuthService, this::log);

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

        requestHandler = new RequestHandler(authService, mailService, fileService, gmailGateway, this::log);
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

        // Khởi động IMAP Polling nếu Gmail Gateway được bật
        if (gmailConfig.isEnabled() && gmailConfig.isConfigured()) {
            startGmailImapSync();
        }
    }

    @FXML
    private void onStop() {
        if (network != null) {
            network.stop();
        }
        if (gmailGateway != null) {
            gmailGateway.stopImapPolling();
        }
        updateTimeline.stop();

        startButton.setDisable(false);
        stopButton.setDisable(true);
        portField.setDisable(false);
        statusLabel.setText("Đã dừng");
        statusIndicator.getStyleClass().remove("status-online");
        statusIndicator.getStyleClass().add("status-offline");
    }

    // =========================================================================
    // GMAIL GATEWAY HANDLERS
    // =========================================================================

    @FXML
    private void onVerifyGmail() {
        String addr = gmailAddressField.getText().trim();
        String pass = gmailPasswordField.getText().trim();
        if (addr.isEmpty() || pass.isEmpty()) {
            log("[Gmail Gateway] ⚠️ Vui lòng nhập địa chỉ Gmail và Mật khẩu ứng dụng trước khi kiểm tra.");
            return;
        }

        btnVerifyGmail.setDisable(true);
        btnVerifyGmail.setText("Đang kiểm tra...");
        log("[Gmail Gateway] Đang kiểm tra xác thực Google SMTP cho " + addr + "...");

        gmailGateway.testConnection(addr, pass).thenAccept(res -> {
            Platform.runLater(() -> {
                btnVerifyGmail.setDisable(false);
                btnVerifyGmail.setText("🔍 Kiểm tra kết nối");
                if (res.isSuccess()) {
                    log("[Gmail Gateway] ✓ XÁC THỰC THÀNH CÔNG! Tài khoản hợp lệ, sẵn sàng chuyển tiếp thư.");
                } else {
                    log("[Gmail Gateway] ✗ THẤT BẠI: " + res.getMessage());
                }
            });
        });
    }

    @FXML
    private void onToggleGmail() {
        if (gmailConfig.isEnabled()) {
            // Tắt tích hợp Gmail
            gmailConfig.setEnabled(false);
            gmailConfig.save();
            if (gmailGateway != null) {
                gmailGateway.stopImapPolling();
            }
            updateGmailUiStatus(false);
            log("[Gmail Gateway] Đã tắt cổng chuyển tiếp Gmail.");
        } else {
            // Bật tích hợp Gmail
            String addr = gmailAddressField.getText().trim();
            String pass = gmailPasswordField.getText().trim();
            if (addr.isEmpty() || pass.isEmpty()) {
                log("[Gmail Gateway] Lỗi: Vui lòng nhập địa chỉ Gmail và Mật khẩu ứng dụng (App Password).");
                return;
            }
            gmailConfig.setGmailAddress(addr);
            gmailConfig.setAppPassword(pass);
            gmailConfig.setEnabled(true);
            gmailConfig.save();
            updateGmailUiStatus(true);
            log("[Gmail Gateway] Đã BẬT cổng chuyển tiếp Gmail cho tài khoản: " + addr);

            if (network != null && network.isRunning()) {
                startGmailImapSync();
            }
        }
    }

    @FXML
    private void onSyncGmail() {
        if (!gmailConfig.isConfigured()) {
            log("[Gmail Gateway] Lỗi: Chưa cấu hình đầy đủ Gmail và App Password để đồng bộ.");
            return;
        }
        log("[Gmail Gateway] Bắt đầu đồng bộ hòm thư Gmail ngay...");
        new Thread(() -> {
            if (gmailGateway != null) {
                gmailGateway.stopImapPolling();
                startGmailImapSync();
            }
        }, "Manual-Gmail-Sync").start();
    }

    private void startGmailImapSync() {
        if (gmailGateway != null) {
            gmailGateway.startImapPolling(this::handleIncomingGmail);
        }
    }

    private void handleIncomingGmail(Email email) {
        if (email == null) return;
        Map<String, String> userEmails = authService.getAllUserEmails();
        Set<String> targetUsers = new HashSet<>();

        // 1. Tìm username nội bộ khớp với email người nhận
        if (email.getRecipients() != null) {
            for (String rec : email.getRecipients()) {
                for (Map.Entry<String, String> entry : userEmails.entrySet()) {
                    if (rec.equalsIgnoreCase(entry.getValue())) {
                        targetUsers.add(entry.getKey());
                    }
                }
            }
        }

        // 2. Tìm nếu người nhận là username trực tiếp
        if (email.getRecipients() != null) {
            for (String rec : email.getRecipients()) {
                if (authService.getUserEmail(rec) != null) {
                    targetUsers.add(rec);
                }
            }
        }

        // 3. Fallback: Nếu không tìm thấy đích danh, chuyển vào hòm thư của tất cả người dùng hệ thống
        if (targetUsers.isEmpty()) {
            targetUsers.addAll(userEmails.keySet());
        }

        for (String user : targetUsers) {
            mailService.receiveExternalMail(user, email);
            log("[Gmail Gateway -> " + user + "] Đã nhận thư '" + email.getSubject() + "' từ " + email.getSender());
        }

        // Đẩy thông báo UDP tới các client đang online
        if (network != null && network.getSocket() != null && requestHandler != null) {
            email.setRecipients(new ArrayList<>(targetUsers));
            requestHandler.notifyOnlineRecipients(email, network.getSocket());
        }
    }

    private void updateGmailUiStatus(boolean active) {
        Platform.runLater(() -> {
            gmailStatusLabel.setText(active ? "Đang hoạt động" : "Đã tắt");
            gmailStatusIndicator.getStyleClass().removeAll("status-online", "status-offline");
            gmailStatusIndicator.getStyleClass().add(active ? "status-online" : "status-offline");
            btnToggleGmail.setText(active ? "Tắt Gmail" : "Bật Gmail");
        });
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
