package com.udpchat.server.controller;

import com.udpchat.database.DatabaseManager;
import com.udpchat.database.EmailDao;
import com.udpchat.database.UserDao;
import com.udpchat.server.pop3.Pop3Server;
import com.udpchat.server.service.GmailForwarder;
import com.udpchat.server.smtp.SmtpServer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

public class ServerController {
    @FXML private Label ipLabel;
    @FXML private TextField smtpPortField;
    @FXML private TextField pop3PortField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Label statusLabel;
    @FXML private Region statusIndicator;

    @FXML private Region dbStatusIndicator;
    @FXML private Label dbStatusLabel;

    @FXML private CheckBox cbEnableGmail;
    @FXML private TextField gmailAccountField;
    @FXML private PasswordField gmailAppPassField;

    @FXML private Label statsLabel;
    @FXML private TextArea logArea;

    private UserDao userDao;
    private EmailDao emailDao;
    private GmailForwarder gmailForwarder;
    private SmtpServer smtpServer;
    private Pop3Server pop3Server;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        ipLabel.setText(detectLocalIP());
        smtpPortField.setText("2525");
        pop3PortField.setText("1110");
        stopButton.setDisable(true);

        log("Khởi động ứng dụng Mail Server...");

        // Khởi tạo Database
        new Thread(() -> {
            DatabaseManager.initialize();
            userDao = new UserDao();
            emailDao = new EmailDao();
            gmailForwarder = new GmailForwarder(this::log);

            Platform.runLater(() -> {
                dbStatusLabel.setText(DatabaseManager.getDatabaseDescription());
                if (DatabaseManager.getCurrentType() == DatabaseManager.DatabaseType.MYSQL) {
                    dbStatusIndicator.getStyleClass().removeAll("status-offline");
                    dbStatusIndicator.getStyleClass().add("status-online");
                } else {
                    dbStatusIndicator.getStyleClass().removeAll("status-offline");
                    dbStatusIndicator.getStyleClass().add("status-online");
                }
                updateStats();
                // Tự động khởi chạy server
                onStart();
            });
        }, "DB-Init-Thread").start();
    }

    @FXML
    public void onStart() {
        try {
            int smtpPort = Integer.parseInt(smtpPortField.getText().trim());
            int pop3Port = Integer.parseInt(pop3PortField.getText().trim());

            if (smtpServer != null && smtpServer.isRunning()) smtpServer.stop();
            if (pop3Server != null && pop3Server.isRunning()) pop3Server.stop();

            // Cập nhật cấu hình Gmail
            applyGmailConfig();

            smtpServer = new SmtpServer(smtpPort, emailDao, gmailForwarder, this::log);
            smtpServer.start();

            pop3Server = new Pop3Server(pop3Port, userDao, emailDao, this::log);
            pop3Server.start();

            statusLabel.setText("Đang chạy (SMTP: " + smtpPort + " | POP3: " + pop3Port + ")");
            statusIndicator.getStyleClass().removeAll("status-offline");
            statusIndicator.getStyleClass().add("status-online");

            startButton.setDisable(true);
            stopButton.setDisable(false);
            smtpPortField.setDisable(true);
            pop3PortField.setDisable(true);

            updateStats();
        } catch (Exception e) {
            log("[LỖI KHỞI ĐỘNG] " + e.getMessage());
            showError("Lỗi khởi động", e.getMessage());
        }
    }

    @FXML
    public void onStop() {
        if (smtpServer != null) smtpServer.stop();
        if (pop3Server != null) pop3Server.stop();

        statusLabel.setText("Đã dừng");
        statusIndicator.getStyleClass().removeAll("status-online");
        statusIndicator.getStyleClass().add("status-offline");

        startButton.setDisable(false);
        stopButton.setDisable(true);
        smtpPortField.setDisable(false);
        pop3PortField.setDisable(false);
    }

    @FXML
    public void onCopyIp() {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(ipLabel.getText());
        Clipboard.getSystemClipboard().setContent(cc);
        log("Đã sao chép IP máy chủ vào bộ nhớ tạm: " + ipLabel.getText());
    }

    @FXML
    public void onSaveGmailConfig() {
        applyGmailConfig();
        showInfo("Cấu hình Gmail", "Đã lưu cấu hình chuyển tiếp Gmail thành công!");
    }

    private void applyGmailConfig() {
        boolean enabled = cbEnableGmail.isSelected();
        String account = gmailAccountField.getText();
        String pass = gmailAppPassField.getText();
        GmailForwarder.configure(account, pass, enabled);
        if (enabled && !account.trim().isEmpty() && !pass.trim().isEmpty()) {
            log("[Gmail] Đã bật chuyển tiếp ra Gmail qua tài khoản: " + account);
        } else if (enabled) {
            log("[Gmail] Cảnh báo: Vui lòng nhập đủ tài khoản Gmail và Mật khẩu ứng dụng 16 ký tự.");
        }
    }

    @FXML
    public void onClearLog() {
        logArea.clear();
    }

    public void log(String message) {
        String timestamp = LocalTime.now().format(TIME_FMT);
        Platform.runLater(() -> {
            logArea.appendText("[" + timestamp + "] " + message + "\n");
            updateStats();
        });
    }

    private void updateStats() {
        if (emailDao != null && statsLabel != null) {
            int total = emailDao.countEmails();
            statsLabel.setText("Tổng số thư trong CSDL: " + total);
        }
    }

    private String detectLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress() && addr.getHostAddress().contains(".")) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    public void shutdown() {
        onStop();
        if (gmailForwarder != null) gmailForwarder.shutdown();
    }
}
