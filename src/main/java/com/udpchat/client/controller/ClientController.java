package com.udpchat.client.controller;

import com.udpchat.client.service.ClientService;
import com.udpchat.shared.util.UDPUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// Điều khiển giao diện người dùng FXML.
public class ClientController {

    @FXML private TextField serverHostField;
    @FXML private Button copyServerIpButton;
    @FXML private TextField portField;
    @FXML private Label myIpLabel;
    @FXML private Button copyMyIpButton;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField filenameField;
    @FXML private TextArea chatArea;
    @FXML private VBox logContainer;
    @FXML private TextArea logArea;
    @FXML private Button toggleLogButton;
    @FXML private Button clearLogButton;
    @FXML private Button registerButton;
    @FXML private Button loginButton;
    @FXML private Button logoutButton;
    @FXML private Button sendButton;
    @FXML private Button uploadButton;
    @FXML private Button downloadButton;
    @FXML private Label statusLabel;
    @FXML private Region statusIndicator;
    @FXML private ProgressBar progressBar;
    @FXML private TextField messageField;

    private ClientService service;
    private DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        serverHostField.setText("localhost");
        portField.setText("8888");
        myIpLabel.setText(UDPUtil.getLocalIPAddress());
        chatArea.setEditable(false);
        logArea.setEditable(false);
        setLoggedInState(false);
    }

    private void setLoggedInState(boolean loggedIn) {
        sendButton.setDisable(!loggedIn);
        uploadButton.setDisable(!loggedIn);
        downloadButton.setDisable(!loggedIn);
        logoutButton.setDisable(!loggedIn);
        
        loginButton.setDisable(loggedIn);
        registerButton.setDisable(loggedIn);
        
        if (loggedIn) {
            statusLabel.setText("ONLINE");
            statusIndicator.getStyleClass().setAll("status-indicator", "status-online");
        } else {
            statusLabel.setText("OFFLINE");
            statusIndicator.getStyleClass().setAll("status-indicator", "status-offline");
            chatArea.clear();
        }
    }

    private void addChatMessage(String formattedMessage) {
        Platform.runLater(() -> {
            chatArea.appendText(formattedMessage + "\n");
        });
    }

    private void addLogMessage(String message) {
        Platform.runLater(() -> {
            String time = LocalTime.now().format(timeFormatter);
            logArea.appendText("[" + time + "] " + message + "\n");
        });
    }

    @FXML
    private void onCopyServerIp() {
        String host = serverHostField.getText().trim();
        if (host.isEmpty()) host = "localhost";
        copyToClipboard(host, copyServerIpButton, "📋 Copy");
    }

    @FXML
    private void onCopyMyIp() {
        String ip = myIpLabel.getText().trim();
        copyToClipboard(ip, copyMyIpButton, "📋 Copy");
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
        addLogMessage("Đã xóa nhật ký hiển thị trên màn hình.");
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

    @FXML
    private void onRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();
        if (username.isEmpty() || password.isEmpty()) {
            addLogMessage("Tên đăng nhập và mật khẩu không được để trống.");
            return;
        }

        new Thread(() -> {
            try {
                if (service == null) {
                    service = new ClientService(serverHostField.getText().trim(), Integer.parseInt(portField.getText().trim()));
                    service.setOnLogMessage(this::addLogMessage);
                }
                service.register(username, password);
            } catch (Exception e) {
                addLogMessage("Lỗi khởi tạo dịch vụ: " + e.getMessage());
            }
        }).start();
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();
        String host = serverHostField.getText().trim();
        String portStr = portField.getText().trim();

        if (username.isEmpty() || password.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
            addLogMessage("Vui lòng điền đủ thông tin kết nối và đăng nhập.");
            return;
        }

        new Thread(() -> {
            try {
                if (service == null) {
                    service = new ClientService(host, Integer.parseInt(portStr));
                    service.setOnLogMessage(this::addLogMessage);
                    service.setOnIncomingMessage(this::addChatMessage);
                }
                String resp = service.login(username, password);
                if (resp.startsWith("RESPONSE|OK")) {
                    Platform.runLater(() -> setLoggedInState(true));
                }
            } catch (Exception e) {
                addLogMessage("Lỗi kết nối khi đăng nhập: " + e.getMessage());
            }
        }).start();
    }

    @FXML
    private void onLogout() {
        if (service != null && service.isLoggedIn()) {
            new Thread(() -> {
                service.logout();
                Platform.runLater(() -> setLoggedInState(false));
            }).start();
        }
    }

    @FXML
    private void onSendMessage() {
        String msg = messageField.getText().trim();
        if (msg.isEmpty()) return;

        new Thread(() -> {
            String resp = service.sendMessage(msg);
            if (resp.startsWith("RESPONSE|OK")) {
                String time = LocalTime.now().format(timeFormatter);
                addChatMessage("[" + time + "] Tôi (me): " + msg);
                Platform.runLater(() -> messageField.clear());
            }
        }).start();
    }

    @FXML
    private void onUpload() {
        Window window = uploadButton.getScene().getWindow();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để tải lên Server");
        File file = fileChooser.showOpenDialog(window);

        if (file != null) {
            new Thread(() -> {
                service.uploadFile(file);
            }).start();
        }
    }

    @FXML
    private void onDownload() {
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) {
            addLogMessage("Vui lòng nhập tên file cần tải xuống.");
            return;
        }

        Window window = downloadButton.getScene().getWindow();
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Chọn thư mục lưu file tải về");
        File dir = directoryChooser.showDialog(window);

        if (dir != null) {
            new Thread(() -> {
                service.downloadFile(filename, dir);
            }).start();
        }
    }
    
    public ClientService getService() {
        return service;
    }
}
