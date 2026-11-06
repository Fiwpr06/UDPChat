package com.udpchat.client.controller;

import com.udpchat.client.pop3.Pop3Client;
import com.udpchat.client.service.GmailDirectClient;
import com.udpchat.client.smtp.SmtpClient;
import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;
import com.udpchat.database.UserDao;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ClientController {

    // Header & User badge
    @FXML private Label lblCurrentUser;
    @FXML private Button btnLogout;

    // Login Pane
    @FXML private VBox loginPane;
    @FXML private TextField serverHostField;
    @FXML private TextField smtpPortField;
    @FXML private TextField pop3PortField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label loginStatusLabel;

    // Main Work Pane
    @FXML private TabPane mainWorkPane;

    // Inbox Tab
    @FXML private Label inboxCountLabel;
    @FXML private ListView<EmailMessage> emailListView;
    @FXML private Label detailSubjectLabel;
    @FXML private Label detailSenderLabel;
    @FXML private Label detailRecipientLabel;
    @FXML private Label detailDateLabel;
    @FXML private TextArea detailBodyArea;
    @FXML private VBox attachmentContainer;
    @FXML private HBox attachmentButtonsBox;

    // Compose Tab
    @FXML private RadioButton rbLocalSmtp;
    @FXML private RadioButton rbDirectGmail;
    @FXML private HBox gmailConfigBox;
    @FXML private TextField directGmailAccountField;
    @FXML private PasswordField directGmailPassField;
    @FXML private TextField sendToField;
    @FXML private TextField sendSubjectField;
    @FXML private TextArea sendBodyArea;
    @FXML private Label composeAttachmentCountLabel;
    @FXML private HBox composeAttachmentListBox;

    // Client Log
    @FXML private TextArea clientLogArea;

    private final List<Attachment> composeAttachments = new ArrayList<>();
    private final UserDao userDao = new UserDao();
    private String currentUsername = null;
    private String currentPassword = null;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        // ToggleGroup cho chế độ gửi thư
        ToggleGroup sendGroup = new ToggleGroup();
        rbLocalSmtp.setToggleGroup(sendGroup);
        rbDirectGmail.setToggleGroup(sendGroup);

        rbDirectGmail.selectedProperty().addListener((obs, oldV, isDirect) -> {
            gmailConfigBox.setVisible(isDirect);
            gmailConfigBox.setManaged(isDirect);
        });

        // Thiết lập CellFactory cho ListView hộp thư đến
        emailListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(EmailMessage item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String attachTag = item.hasAttachments() ? " 📎[" + item.getAttachments().size() + " tệp]" : "";
                    setText("[" + item.getSentAt() + "] " + item.getSender() + ": " + item.getSubject() + attachTag);
                }
            }
        });

        // Lắng nghe sự kiện chọn thư trong danh sách
        emailListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            displayEmailDetail(newVal);
        });

        log("Khởi động Mail Client hoàn tất. Sẵn sàng kết nối.");
    }

    @FXML
    public void onLogin() {
        String host = serverHostField.getText().trim();
        int pop3Port;
        try {
            pop3Port = Integer.parseInt(pop3PortField.getText().trim());
        } catch (NumberFormatException e) {
            showError("Lỗi", "Cổng POP3 không hợp lệ.");
            return;
        }

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            loginStatusLabel.setText("Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.");
            return;
        }

        loginStatusLabel.setText("Đang xác thực qua POP3 tại " + host + ":" + pop3Port + "...");
        log("Gửi yêu cầu xác thực POP3 cho tài khoản: " + username);

        new Thread(() -> {
            Pop3Client.Pop3Result result = Pop3Client.fetchEmails(host, pop3Port, username, password);
            Platform.runLater(() -> {
                if (result.success) {
                    currentUsername = username;
                    currentPassword = password;

                    lblCurrentUser.setText("👤 " + currentUsername + "@localhost");
                    btnLogout.setVisible(true);

                    loginPane.setVisible(false);
                    loginPane.setManaged(false);

                    mainWorkPane.setVisible(true);
                    mainWorkPane.setManaged(true);

                    log("✓ Đăng nhập thành công! Hộp thư có " + result.emails.size() + " thư.");
                    updateInboxUI(result.emails);
                } else {
                    loginStatusLabel.setText("Đăng nhập thất bại: " + result.message);
                    log("✗ Lỗi đăng nhập POP3: " + result.message);
                }
            });
        }, "Client-Login-Thread").start();
    }

    @FXML
    public void onRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Lỗi đăng ký", "Vui lòng nhập tên tài khoản và mật khẩu muốn đăng ký.");
            return;
        }

        if (userDao.existsByUsername(username)) {
            showError("Lỗi", "Tài khoản '" + username + "' đã tồn tại!");
            return;
        }

        boolean ok = userDao.register(username, password, username + "@localhost");
        if (ok) {
            showInfo("Thành công", "Đã tạo tài khoản '" + username + "' thành công. Bạn có thể nhấn 'Đăng nhập' ngay.");
            log("Đã đăng ký tài khoản mới: " + username);
        } else {
            showError("Lỗi", "Không thể tạo tài khoản vào CSDL.");
        }
    }

    @FXML
    public void onLogout() {
        currentUsername = null;
        currentPassword = null;
        lblCurrentUser.setText("Chưa đăng nhập");
        btnLogout.setVisible(false);

        mainWorkPane.setVisible(false);
        mainWorkPane.setManaged(false);

        loginPane.setVisible(true);
        loginPane.setManaged(true);

        emailListView.getItems().clear();
        clearEmailDetail();
        log("Đã đăng xuất khỏi hệ thống.");
    }

    @FXML
    public void onRefreshInbox() {
        if (currentUsername == null) return;

        String host = serverHostField.getText().trim();
        int pop3Port = Integer.parseInt(pop3PortField.getText().trim());

        log("[POP3] Đang tải danh sách thư mới từ " + host + ":" + pop3Port + "...");
        new Thread(() -> {
            Pop3Client.Pop3Result result = Pop3Client.fetchEmails(host, pop3Port, currentUsername, currentPassword);
            Platform.runLater(() -> {
                if (result.success) {
                    updateInboxUI(result.emails);
                    log("[POP3] ✓ Tải thư thành công. Tìm thấy " + result.emails.size() + " thư.");
                } else {
                    showError("Lỗi tải thư", result.message);
                    log("[POP3] ✗ Lỗi: " + result.message);
                }
            });
        }, "Refresh-Inbox-Thread").start();
    }

    @FXML
    public void onDeleteSelectedMail() {
        int selectedIndex = emailListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) {
            showInfo("Thông báo", "Vui lòng chọn một thư trong danh sách để xóa.");
            return;
        }

        String host = serverHostField.getText().trim();
        int pop3Port = Integer.parseInt(pop3PortField.getText().trim());

        log("[POP3 DELE] Đang gửi lệnh xóa thư vị trí " + (selectedIndex + 1) + "...");
        new Thread(() -> {
            boolean ok = Pop3Client.deleteEmail(host, pop3Port, currentUsername, currentPassword, selectedIndex + 1);
            Platform.runLater(() -> {
                if (ok) {
                    log("[POP3 DELE] ✓ Đã xóa thư thành công trên máy chủ.");
                    onRefreshInbox();
                    clearEmailDetail();
                } else {
                    showError("Lỗi xóa thư", "Không thể xóa thư trên máy chủ POP3.");
                }
            });
        }, "Delete-Mail-Thread").start();
    }

    private void updateInboxUI(List<EmailMessage> emails) {
        emailListView.getItems().clear();
        if (emails != null) {
            emailListView.getItems().addAll(emails);
            inboxCountLabel.setText("Tổng số: " + emails.size() + " thư");
        } else {
            inboxCountLabel.setText("Tổng số: 0 thư");
        }
    }

    private void displayEmailDetail(EmailMessage email) {
        if (email == null) {
            clearEmailDetail();
            return;
        }

        detailSubjectLabel.setText(email.getSubject());
        detailSenderLabel.setText(email.getSender());
        detailRecipientLabel.setText(email.getRecipient());
        detailDateLabel.setText(email.getSentAt());
        detailBodyArea.setText(email.getBody());

        // Hiển thị tệp đính kèm nếu có
        attachmentButtonsBox.getChildren().clear();
        if (email.hasAttachments()) {
            attachmentContainer.setVisible(true);
            attachmentContainer.setManaged(true);

            for (Attachment att : email.getAttachments()) {
                Button btnDownload = new Button("💾 " + att.getFilename() + " (" + att.getFormattedSize() + ")");
                btnDownload.getStyleClass().add("button-secondary");
                btnDownload.setOnAction(e -> downloadAttachment(att));
                attachmentButtonsBox.getChildren().add(btnDownload);
            }
        } else {
            attachmentContainer.setVisible(false);
            attachmentContainer.setManaged(false);
        }
    }

    private void clearEmailDetail() {
        detailSubjectLabel.setText("(Chọn một thư từ danh sách bên trái để đọc)");
        detailSenderLabel.setText("-");
        detailRecipientLabel.setText("-");
        detailDateLabel.setText("-");
        detailBodyArea.clear();
        attachmentContainer.setVisible(false);
        attachmentContainer.setManaged(false);
        attachmentButtonsBox.getChildren().clear();
    }

    private void downloadAttachment(Attachment att) {
        if (att.getData() == null || att.getData().length == 0) {
            showError("Lỗi tải tệp", "Tệp đính kèm không có dữ liệu nội dung.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Lưu tệp đính kèm");
        fileChooser.setInitialFileName(att.getFilename());

        File saveFile = fileChooser.showSaveDialog(mainWorkPane.getScene().getWindow());
        if (saveFile != null) {
            try {
                Files.write(saveFile.toPath(), att.getData());
                showInfo("Tải tệp thành công", "Đã lưu tệp vào:\n" + saveFile.getAbsolutePath());
                log("Đã lưu tệp đính kèm: " + saveFile.getName());
            } catch (Exception e) {
                showError("Lỗi lưu tệp", e.getMessage());
            }
        }
    }

    // --- SOẠN THƯ (SMTP) ---

    @FXML
    public void onAddAttachment() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn tệp đính kèm");
        List<File> files = fileChooser.showOpenMultipleDialog(mainWorkPane.getScene().getWindow());

        if (files != null && !files.isEmpty()) {
            for (File f : files) {
                try {
                    byte[] data = Files.readAllBytes(f.toPath());
                    Attachment att = new Attachment(f.getName(), data.length, data);
                    composeAttachments.add(att);
                } catch (Exception e) {
                    showError("Lỗi nạp tệp", "Không thể đọc tệp " + f.getName() + ": " + e.getMessage());
                }
            }
            updateComposeAttachmentsUI();
        }
    }

    private void updateComposeAttachmentsUI() {
        composeAttachmentListBox.getChildren().clear();
        if (composeAttachments.isEmpty()) {
            composeAttachmentCountLabel.setText("Chưa có tệp nào");
            return;
        }

        composeAttachmentCountLabel.setText(composeAttachments.size() + " tệp được chọn:");
        for (Attachment att : composeAttachments) {
            HBox chip = new HBox(6);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setStyle("-fx-background-color: #1E293B; -fx-padding: 3px 8px; -fx-background-radius: 4px; -fx-border-color: #334155; -fx-border-radius: 4px;");

            Label lbl = new Label(att.getFilename() + " (" + att.getFormattedSize() + ")");
            lbl.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");

            Button btnRemove = new Button("✕");
            btnRemove.setStyle("-fx-background-color: transparent; -fx-text-fill: #EF4444; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 0;");
            btnRemove.setOnAction(e -> {
                composeAttachments.remove(att);
                updateComposeAttachmentsUI();
            });

            chip.getChildren().addAll(lbl, btnRemove);
            composeAttachmentListBox.getChildren().add(chip);
        }
    }

    @FXML
    public void onClearCompose() {
        sendToField.clear();
        sendSubjectField.clear();
        sendBodyArea.clear();
        composeAttachments.clear();
        updateComposeAttachmentsUI();
    }

    @FXML
    public void onSendEmail() {
        String to = sendToField.getText().trim();
        String subject = sendSubjectField.getText().trim();
        String body = sendBodyArea.getText();

        if (to.isEmpty()) {
            showError("Lỗi", "Vui lòng nhập địa chỉ người nhận (To).");
            return;
        }

        EmailMessage email = new EmailMessage();
        email.setSender(currentUsername != null ? currentUsername : "user@localhost");
        email.setRecipient(to);
        email.setSubject(subject);
        email.setBody(body);
        email.setAttachments(new ArrayList<>(composeAttachments));

        if (rbDirectGmail.isSelected()) {
            // Chế độ gửi trực tiếp qua Gmail SMTP (TLS 587)
            String gmailAccount = directGmailAccountField.getText();
            String appPassword = directGmailPassField.getText();

            log("[Gmail Direct] Đang gửi email '" + subject + "' tới " + to + " qua smtp.gmail.com:587...");
            new Thread(() -> {
                GmailDirectClient.SendResult res = GmailDirectClient.sendViaGmail(gmailAccount, appPassword, email);
                Platform.runLater(() -> {
                    if (res.success) {
                        showInfo("Thành công", res.message);
                        log("[Gmail Direct] ✓ " + res.message);
                        onClearCompose();
                    } else {
                        showError("Lỗi gửi Gmail", res.message);
                        log("[Gmail Direct] ✗ " + res.message);
                    }
                });
            }, "Gmail-Send-Thread").start();
        } else {
            // Chế độ gửi qua Localhost SMTP Server (Cổng 2525)
            String host = serverHostField.getText().trim();
            int smtpPort;
            try {
                smtpPort = Integer.parseInt(smtpPortField.getText().trim());
            } catch (Exception e) {
                showError("Lỗi", "Cổng SMTP không hợp lệ.");
                return;
            }

            log("[SMTP Local] Đang gửi email '" + subject + "' tới " + to + " qua " + host + ":" + smtpPort + "...");
            new Thread(() -> {
                SmtpClient.SmtpResult res = SmtpClient.sendEmail(host, smtpPort, email);
                Platform.runLater(() -> {
                    if (res.success) {
                        showInfo("Thành công", res.message);
                        log("[SMTP Local] ✓ " + res.message);
                        onClearCompose();
                        // Tải lại inbox nếu gửi cho chính mình
                        if (to.equalsIgnoreCase(currentUsername) || to.equalsIgnoreCase(currentUsername + "@localhost")) {
                            onRefreshInbox();
                        }
                    } else {
                        showError("Lỗi gửi thư", res.message);
                        log("[SMTP Local] ✗ " + res.message);
                    }
                });
            }, "Smtp-Send-Thread").start();
        }
    }

    @FXML
    public void onClearClientLog() {
        clientLogArea.clear();
    }

    public void log(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        Platform.runLater(() -> {
            clientLogArea.appendText("[" + time + "] " + msg + "\n");
        });
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
}
