package com.udpchat.client.controller;

import com.udpchat.client.service.ClientService;
import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.util.UDPUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Điều khiển giao diện Máy khách (Mail Client) theo đúng chuẩn Gmail
 */
public class ClientController {

    // SCREEN 1: KẾT NỐI
    @FXML private VBox connectScreen;
    @FXML private TextField serverHostField;
    @FXML private TextField portField;
    @FXML private Label myIpLabel;
    @FXML private Button copyMyIpButton;
    @FXML private Label connectErrorLabel;
    @FXML private Button btnConnectServer;

    // SCREEN 2: XÁC THỰC
    @FXML private VBox authScreen;
    @FXML private Label authServerBadge;
    @FXML private Label authModeTitle;
    @FXML private Label authModeSubtitle;
    @FXML private Button tabLoginButton;
    @FXML private Button tabRegisterButton;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label authStatusLabel;
    @FXML private Button authActionButton;

    // SCREEN 3: WORKSPACE CHÍNH
    @FXML private BorderPane mainMailScreen;
    @FXML private TextField searchField;
    @FXML private Button btnSearch;
    @FXML private Button btnClearSearch;
    @FXML private Label currentUserBadge;

    // SIDEBAR
    @FXML private Button btnOpenCompose;
    @FXML private Button btnFolderInbox;
    @FXML private Label inboxBadgeCount;
    @FXML private Button btnFolderStarred;
    @FXML private Button btnFolderSent;
    @FXML private Button btnFolderDrafts;
    @FXML private Label draftsBadgeCount;
    @FXML private Button btnFolderTrash;
    @FXML private Label userEmailDisplayLabel;
    @FXML private Label serverAddressDisplayLabel;
    @FXML private Button btnToggleLog;
    @FXML private Button btnLogout;

    // SWAPPABLE VIEWS: LIST VIEW VS DETAIL VIEW (GMAIL STYLE)
    @FXML private VBox mailListView;
    @FXML private Label currentFolderTitle;
    @FXML private Button btnPrevPage;
    @FXML private Label pageInfoLabel;
    @FXML private Button btnNextPage;
    @FXML private ScrollPane mailListScrollPane;
    @FXML private VBox mailListBox;
    @FXML private VBox emptyMailboxPlaceholder;

    // DETAIL VIEW
    @FXML private VBox mailDetailView;
    @FXML private Button btnBackToList;
    @FXML private Button btnReply;
    @FXML private Button btnReplyAll;
    @FXML private Button btnForward;
    @FXML private Button btnToggleStarDetail;
    @FXML private Button btnDeleteDetail;
    @FXML private Label detailTimeLabel;

    @FXML private Label detailSubjectLabel;
    @FXML private Label detailAvatarLabel;
    @FXML private Label detailSenderLabel;
    @FXML private Label detailRecipientsLabel;
    @FXML private Label detailCcLabel;
    @FXML private Label detailBodyLabel;

    @FXML private VBox attachmentsSection;
    @FXML private Label attachmentsHeaderLabel;
    @FXML private FlowPane attachmentsFlowPane;

    // BOTTOM: UDP LOGS
    @FXML private VBox logDrawer;
    @FXML private TextArea logArea;

    // FLOATING COMPOSE MODAL
    @FXML private VBox composeOverlay;
    @FXML private Label composeTitleLabel;
    @FXML private TextField composeToField;
    @FXML private Button btnToggleCcBcc;
    @FXML private VBox ccBccContainer;
    @FXML private TextField composeCcField;
    @FXML private TextField composeBccField;
    @FXML private TextField composeSubjectField;
    @FXML private TextArea composeBodyArea;
    @FXML private VBox composeAttachmentsBox;
    @FXML private FlowPane composeAttachmentsFlowPane;
    @FXML private ProgressBar composeUploadProgress;
    @FXML private Button btnSendMailAction;
    @FXML private Button btnAttachFile;
    @FXML private Button btnSaveDraftAction;

    // STATE
    private ClientService service;
    private boolean isLoginMode = true;
    private MailFolder currentFolder = MailFolder.INBOX;
    private int currentPage = 1;
    private final int pageSize = 20;
    private int totalPages = 1;
    private Email currentSelectedMail = null;
    private String replyToId = null;

    // Compose attached files
    private final List<File> composeAttachedFiles = new ArrayList<>();

    @FXML
    public void initialize() {
        myIpLabel.setText(UDPUtil.getLocalIPAddress());
        showConnectScreen();
    }

    // =========================================================================
    // SCREEN 1: KẾT NỐI SERVER
    // =========================================================================
    @FXML
    private void onUseDefaultPort() {
        portField.setText("8888");
    }

    @FXML
    private void onCopyMyIp() {
        copyToClipboard(myIpLabel.getText().trim(), copyMyIpButton, "Sao chép");
    }

    @FXML
    private void onConnectServer() {
        String host = serverHostField.getText().trim();
        String portStr = portField.getText().trim();

        if (host.isEmpty() || portStr.isEmpty()) {
            showConnectError("Vui lòng điền đầy đủ Địa chỉ Máy chủ và Cổng dịch vụ");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showConnectError("Cổng dịch vụ không hợp lệ (phải từ 1 đến 65535)");
            return;
        }

        try {
            if (service != null) service.close();
            service = new ClientService(host, port);
            service.setOnLogMessage(this::appendLog);
            service.setOnNewMailNotification(this::handleNewMailPush);

            authServerBadge.setText(host + ":" + port);
            serverAddressDisplayLabel.setText(host + ":" + port);
            hideConnectError();
            showAuthScreen();
        } catch (Exception e) {
            showConnectError("Không thể khởi tạo socket UDP: " + e.getMessage());
        }
    }

    private void showConnectError(String msg) {
        connectErrorLabel.setText(msg);
        connectErrorLabel.setVisible(true);
        connectErrorLabel.setManaged(true);
    }

    private void hideConnectError() {
        connectErrorLabel.setVisible(false);
        connectErrorLabel.setManaged(false);
    }

    // =========================================================================
    // SCREEN 2: XÁC THỰC (LOGIN / REGISTER)
    // =========================================================================
    @FXML
    private void onBackToConnect() {
        if (service != null) {
            service.close();
            service = null;
        }
        showConnectScreen();
    }

    @FXML
    private void onSelectLoginTab() {
        isLoginMode = true;
        tabLoginButton.getStyleClass().add("tab-btn-active");
        tabRegisterButton.getStyleClass().remove("tab-btn-active");
        authModeTitle.setText("Đăng nhập Hộp thư");
        authModeSubtitle.setText("Nhập tài khoản để truy cập hệ thống thư điện tử UDP");
        authActionButton.setText("Đăng nhập ngay");
        hideAuthStatus();
    }

    @FXML
    private void onSelectRegisterTab() {
        isLoginMode = false;
        tabRegisterButton.getStyleClass().add("tab-btn-active");
        tabLoginButton.getStyleClass().remove("tab-btn-active");
        authModeTitle.setText("Đăng ký Tài khoản mới");
        authModeSubtitle.setText("Tạo tài khoản để nhận địa chỉ hộp thư điện tử cá nhân");
        authActionButton.setText("Tạo tài khoản");
        hideAuthStatus();
    }

    @FXML
    private void onAuthAction() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showAuthStatus("Vui lòng nhập đầy đủ Tên đăng nhập và Mật khẩu", false);
            return;
        }

        authActionButton.setDisable(true);

        new Thread(() -> {
            String result;
            if (isLoginMode) {
                result = service.login(username, password);
            } else {
                result = service.register(username, password);
            }

            Platform.runLater(() -> {
                authActionButton.setDisable(false);
                if (result.startsWith("RESPONSE|OK") || result.contains("Thành công") || result.startsWith("OK")) {
                    if (isLoginMode) {
                        currentUserBadge.setText(username);
                        userEmailDisplayLabel.setText(username + "@udpmail");
                        showMainMailScreen();
                        showMailListView();
                        loadMails(MailFolder.INBOX, 1);
                    } else {
                        showAuthStatus("Đăng ký thành công! Hãy chuyển sang Đăng nhập.", true);
                        onSelectLoginTab();
                    }
                } else {
                    String err = result;
                    if (result.contains("|")) {
                        String[] p = result.split("\\|");
                        err = p.length >= 3 ? p[2] : (p.length >= 2 ? p[1] : result);
                    }
                    showAuthStatus(err, false);
                }
            });
        }).start();
    }

    private void showAuthStatus(String msg, boolean isSuccess) {
        authStatusLabel.setText(msg);
        authStatusLabel.getStyleClass().removeAll("feedback-success", "feedback-error");
        authStatusLabel.getStyleClass().add(isSuccess ? "feedback-success" : "feedback-error");
        authStatusLabel.setVisible(true);
        authStatusLabel.setManaged(true);
    }

    private void hideAuthStatus() {
        authStatusLabel.setVisible(false);
        authStatusLabel.setManaged(false);
    }

    // =========================================================================
    // SCREEN 3: MAIL WORKSPACE NAVIGATION & FOLDERS
    // =========================================================================
    @FXML
    private void onSelectFolderInbox() {
        switchFolder(MailFolder.INBOX, btnFolderInbox);
    }

    @FXML
    private void onSelectFolderStarred() {
        switchFolder(MailFolder.STARRED, btnFolderStarred);
    }

    @FXML
    private void onSelectFolderSent() {
        switchFolder(MailFolder.SENT, btnFolderSent);
    }

    @FXML
    private void onSelectFolderDrafts() {
        switchFolder(MailFolder.DRAFTS, btnFolderDrafts);
    }

    @FXML
    private void onSelectFolderTrash() {
        switchFolder(MailFolder.TRASH, btnFolderTrash);
    }

    private void switchFolder(MailFolder folder, Button activeBtn) {
        currentFolder = folder;
        currentPage = 1;

        // Reset button active styles
        List<Button> buttons = List.of(btnFolderInbox, btnFolderStarred, btnFolderSent, btnFolderDrafts, btnFolderTrash);
        for (Button b : buttons) {
            b.getStyleClass().remove("folder-nav-btn-active");
        }
        if (activeBtn != null) {
            activeBtn.getStyleClass().add("folder-nav-btn-active");
        }

        currentFolderTitle.setText(folder.getDisplayName());
        showMailListView();
        loadMails(folder, 1);
    }

    @FXML
    private void onRefreshCurrentFolder() {
        showMailListView();
        loadMails(currentFolder, currentPage);
    }

    @FXML
    private void onPrevPage() {
        if (currentPage > 1) {
            loadMails(currentFolder, currentPage - 1);
        }
    }

    @FXML
    private void onNextPage() {
        if (currentPage < totalPages) {
            loadMails(currentFolder, currentPage + 1);
        }
    }

    // =========================================================================
    // VIEW SWITCHING: LIST VIEW <-> DETAIL VIEW (GMAIL STYLE)
    // =========================================================================
    private void showMailListView() {
        mailListView.setVisible(true);
        mailListView.setManaged(true);
        mailDetailView.setVisible(false);
        mailDetailView.setManaged(false);
    }

    private void showMailDetailView() {
        mailListView.setVisible(false);
        mailListView.setManaged(false);
        mailDetailView.setVisible(true);
        mailDetailView.setManaged(true);
    }

    @FXML
    private void onBackToList() {
        showMailListView();
        loadMails(currentFolder, currentPage);
    }

    // =========================================================================
    // MAIL LIST RENDERING (GMAIL HORIZONTAL ROW STYLE)
    // =========================================================================
    private void loadMails(MailFolder folder, int page) {
        new Thread(() -> {
            ClientService.MailListResult res = service.listMails(folder, page, pageSize);
            Platform.runLater(() -> {
                currentPage = page;
                int totalMails = res.total;
                totalPages = Math.max(1, (int) Math.ceil((double) totalMails / pageSize));
                pageInfoLabel.setText(currentPage + " / " + totalPages);
                btnPrevPage.setDisable(currentPage <= 1);
                btnNextPage.setDisable(currentPage >= totalPages);

                // Update unread badge on inbox
                if (res.unreadInbox > 0) {
                    inboxBadgeCount.setText(String.valueOf(res.unreadInbox));
                    inboxBadgeCount.setVisible(true);
                    inboxBadgeCount.setManaged(true);
                } else {
                    inboxBadgeCount.setVisible(false);
                    inboxBadgeCount.setManaged(false);
                }

                renderMailList(res.emails);
            });
        }).start();
    }

    private void renderMailList(List<Email> emails) {
        mailListBox.getChildren().clear();

        if (emails == null || emails.isEmpty()) {
            emptyMailboxPlaceholder.setVisible(true);
            emptyMailboxPlaceholder.setManaged(true);
            mailListScrollPane.setVisible(false);
            return;
        }

        emptyMailboxPlaceholder.setVisible(false);
        emptyMailboxPlaceholder.setManaged(false);
        mailListScrollPane.setVisible(true);

        for (Email mail : emails) {
            HBox row = createMailRow(mail);
            mailListBox.getChildren().add(row);
        }
    }

    private HBox createMailRow(Email mail) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mail-row-item");
        if (!mail.isRead()) {
            row.getStyleClass().add("mail-row-unread");
        }

        // 1. Unread indicator dot
        if (!mail.isRead()) {
            Region dot = new Region();
            dot.getStyleClass().add("unread-dot");
            row.getChildren().add(dot);
        } else {
            Region spacer = new Region();
            spacer.setPrefWidth(8);
            row.getChildren().add(spacer);
        }

        // 2. Star Button (★ / ☆)
        Button starBtn = new Button(mail.isStarred() ? "★" : "☆");
        starBtn.getStyleClass().add("star-btn");
        if (mail.isStarred()) starBtn.getStyleClass().add("star-btn-active");
        starBtn.setOnAction(e -> {
            e.consume();
            boolean newStarred = !mail.isStarred();
            mail.setStarred(newStarred);
            starBtn.setText(newStarred ? "★" : "☆");
            if (newStarred) starBtn.getStyleClass().add("star-btn-active");
            else starBtn.getStyleClass().remove("star-btn-active");
            new Thread(() -> service.toggleStar(mail.getId(), newStarred)).start();
        });
        row.getChildren().add(starBtn);

        // 3. Sender or Recipient display (Fixed width ~140px)
        String displayName = currentFolder == MailFolder.SENT ? "Tới: " + mail.getRecipientsDisplay() : mail.getSender();
        Label senderLabel = new Label(displayName);
        senderLabel.getStyleClass().add("mail-row-sender");
        if (!mail.isRead()) {
            senderLabel.getStyleClass().add("mail-row-sender-unread");
        }
        row.getChildren().add(senderLabel);

        // 4. Middle: Subject + " - " + Snippet preview
        HBox middleBox = new HBox(6);
        middleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(middleBox, Priority.ALWAYS);

        Label subjectLabel = new Label(mail.getSubject());
        subjectLabel.getStyleClass().add("mail-row-subject");
        if (!mail.isRead()) {
            subjectLabel.getStyleClass().add("mail-row-subject-unread");
        }

        Label hyphenLabel = new Label("-");
        hyphenLabel.setStyle("-fx-text-fill: #475569;");

        Label snippetLabel = new Label(mail.getPreview(80));
        snippetLabel.getStyleClass().add("mail-row-snippet");

        middleBox.getChildren().addAll(subjectLabel, hyphenLabel, snippetLabel);
        row.getChildren().add(middleBox);

        // 5. Attachment badge if any
        if (mail.hasAttachments()) {
            Label clipLabel = new Label("Tệp");
            clipLabel.getStyleClass().add("attachment-mini-badge");
            row.getChildren().add(clipLabel);
        }

        // 6. Time (right-aligned)
        String displayTime = formatShortTime(mail.getSentAt());
        Label timeLabel = new Label(displayTime);
        timeLabel.getStyleClass().add("mail-row-time");
        row.getChildren().add(timeLabel);

        // Click handler: Open full-width detail view (Gmail style)
        row.setOnMouseClicked(e -> openMailDetail(mail));

        return row;
    }

    private void openMailDetail(Email mail) {
        currentSelectedMail = mail;

        // Fetch full mail detail from server
        new Thread(() -> {
            Email full = service.readMail(mail.getId());
            Platform.runLater(() -> {
                Email target = full != null ? full : mail;
                target.setRead(true);
                displayMailDetail(target);
                showMailDetailView();
            });
        }).start();
    }

    // =========================================================================
    // DETAIL VIEW CONTENT DISPLAY
    // =========================================================================
    private void displayMailDetail(Email mail) {
        currentSelectedMail = mail;

        detailSubjectLabel.setText(mail.getSubject());
        detailSenderLabel.setText(mail.getSender() + " <" + mail.getSender() + "@udpmail>");
        detailTimeLabel.setText(mail.getSentAt());
        detailRecipientsLabel.setText("Đến: " + mail.getRecipientsDisplay());

        String initial = mail.getSender() != null && !mail.getSender().isEmpty()
                ? mail.getSender().substring(0, 1).toUpperCase() : "?";
        detailAvatarLabel.setText(initial);

        if (mail.getCc() != null && !mail.getCc().isEmpty()) {
            detailCcLabel.setText("CC: " + mail.getCcDisplay());
            detailCcLabel.setVisible(true);
            detailCcLabel.setManaged(true);
        } else {
            detailCcLabel.setVisible(false);
            detailCcLabel.setManaged(false);
        }

        detailBodyLabel.setText(mail.getBody());
        btnToggleStarDetail.setText(mail.isStarred() ? "★ Bỏ sao" : "★ Gắn sao");

        // Attachments
        if (mail.hasAttachments()) {
            attachmentsSection.setVisible(true);
            attachmentsSection.setManaged(true);
            attachmentsHeaderLabel.setText("Tệp đính kèm (" + mail.getAttachmentCount() + "):");
            attachmentsFlowPane.getChildren().clear();

            for (Attachment att : mail.getAttachments()) {
                HBox chip = createAttachmentChip(att);
                attachmentsFlowPane.getChildren().add(chip);
            }
        } else {
            attachmentsSection.setVisible(false);
            attachmentsSection.setManaged(false);
        }
    }

    private HBox createAttachmentChip(Attachment att) {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("attachment-card");

        // Type Badge
        Label typeBadge = new Label(att.getContentType());
        typeBadge.getStyleClass().add("attachment-badge");
        box.getChildren().add(typeBadge);

        // Filename and size
        VBox info = new VBox(2);
        Label nameLabel = new Label(att.getFilename());
        nameLabel.getStyleClass().add("user-name-label");
        Label sizeLabel = new Label(att.formatSize());
        sizeLabel.getStyleClass().add("caption-muted");
        info.getChildren().addAll(nameLabel, sizeLabel);
        box.getChildren().add(info);

        // Download button
        Button btnDownload = new Button("Tải về");
        btnDownload.getStyleClass().add("button-secondary");
        btnDownload.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Chọn thư mục lưu tệp: " + att.getFilename());
            File dir = chooser.showDialog(mainMailScreen.getScene().getWindow());
            if (dir != null) {
                btnDownload.setDisable(true);
                btnDownload.setText("Đang tải...");
                new Thread(() -> {
                    boolean ok = service.downloadAttachment(att.getFilename(), dir, null);
                    Platform.runLater(() -> {
                        btnDownload.setDisable(false);
                        btnDownload.setText(ok ? "✓ Đã tải" : "Thử lại");
                    });
                }).start();
            }
        });
        box.getChildren().add(btnDownload);

        return box;
    }

    @FXML
    private void onToggleStarDetail() {
        if (currentSelectedMail == null) return;
        boolean newStarred = !currentSelectedMail.isStarred();
        currentSelectedMail.setStarred(newStarred);
        btnToggleStarDetail.setText(newStarred ? "★ Bỏ sao" : "★ Gắn sao");
        new Thread(() -> service.toggleStar(currentSelectedMail.getId(), newStarred)).start();
    }

    @FXML
    private void onDeleteCurrentMail() {
        if (currentSelectedMail == null) return;
        boolean isTrash = currentFolder == MailFolder.TRASH;
        new Thread(() -> {
            service.deleteMail(currentSelectedMail.getId(), isTrash);
            Platform.runLater(() -> {
                onBackToList();
            });
        }).start();
    }

    // =========================================================================
    // COMPOSE EMAIL (FLOATING GMAIL-STYLE MODAL)
    // =========================================================================
    @FXML
    private void onOpenCompose() {
        composeTitleLabel.setText("Thư mới");
        composeToField.clear();
        composeCcField.clear();
        composeBccField.clear();
        composeSubjectField.clear();
        composeBodyArea.clear();
        composeAttachedFiles.clear();
        replyToId = null;

        ccBccContainer.setVisible(false);
        ccBccContainer.setManaged(false);
        composeAttachmentsBox.setVisible(false);
        composeAttachmentsBox.setManaged(false);
        composeUploadProgress.setVisible(false);
        composeUploadProgress.setManaged(false);

        showComposeOverlay();
    }

    @FXML
    private void onCloseCompose() {
        hideComposeOverlay();
    }

    @FXML
    private void onToggleCcBcc() {
        boolean vis = !ccBccContainer.isVisible();
        ccBccContainer.setVisible(vis);
        ccBccContainer.setManaged(vis);
    }

    @FXML
    private void onReplyMail() {
        if (currentSelectedMail == null) return;
        onOpenCompose();
        composeTitleLabel.setText("Trả lời: " + currentSelectedMail.getSubject());
        composeToField.setText(currentSelectedMail.getSender());
        String subj = currentSelectedMail.getSubject();
        if (!subj.startsWith("Re:")) subj = "Re: " + subj;
        composeSubjectField.setText(subj);
        composeBodyArea.setText("\n\n--- Thư gốc từ " + currentSelectedMail.getSender() + " (" + currentSelectedMail.getSentAt() + ") ---\n" + currentSelectedMail.getBody());
        replyToId = currentSelectedMail.getId();
    }

    @FXML
    private void onReplyAllMail() {
        if (currentSelectedMail == null) return;
        onReplyMail();
        composeTitleLabel.setText("Trả lời tất cả: " + currentSelectedMail.getSubject());
        List<String> ccList = new ArrayList<>(currentSelectedMail.getRecipients());
        if (currentSelectedMail.getCc() != null) ccList.addAll(currentSelectedMail.getCc());
        ccList.remove(service.getCurrentUsername());
        ccList.remove(currentSelectedMail.getSender());
        if (!ccList.isEmpty()) {
            composeCcField.setText(String.join(", ", ccList));
            ccBccContainer.setVisible(true);
            ccBccContainer.setManaged(true);
        }
    }

    @FXML
    private void onForwardMail() {
        if (currentSelectedMail == null) return;
        onOpenCompose();
        composeTitleLabel.setText("Chuyển tiếp: " + currentSelectedMail.getSubject());
        composeToField.clear();
        String subj = currentSelectedMail.getSubject();
        if (!subj.startsWith("Fwd:")) subj = "Fwd: " + subj;
        composeSubjectField.setText(subj);
        composeBodyArea.setText("\n\n--- Thư chuyển tiếp từ " + currentSelectedMail.getSender() + " (" + currentSelectedMail.getSentAt() + ") ---\n" + currentSelectedMail.getBody());
    }

    @FXML
    private void onAttachFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm gửi qua UDP");
        List<File> files = chooser.showOpenMultipleDialog(mainMailScreen.getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            composeAttachedFiles.addAll(files);
            refreshComposeAttachments();
        }
    }

    private void refreshComposeAttachments() {
        if (composeAttachedFiles.isEmpty()) {
            composeAttachmentsBox.setVisible(false);
            composeAttachmentsBox.setManaged(false);
            return;
        }

        composeAttachmentsBox.setVisible(true);
        composeAttachmentsBox.setManaged(true);
        composeAttachmentsFlowPane.getChildren().clear();

        for (File f : composeAttachedFiles) {
            HBox chip = new HBox(6);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("attachment-card");

            Label name = new Label(f.getName() + " (" + formatFileSize(f.length()) + ")");
            name.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #F8FAFC;");

            Button btnRemove = new Button("✕");
            btnRemove.getStyleClass().add("button-secondary");
            btnRemove.setStyle("-fx-padding: 1px 6px; -fx-font-size: 10px;");
            btnRemove.setOnAction(e -> {
                composeAttachedFiles.remove(f);
                refreshComposeAttachments();
            });

            chip.getChildren().addAll(name, btnRemove);
            composeAttachmentsFlowPane.getChildren().add(chip);
        }
    }

    @FXML
    private void onSendMail() {
        String toStr = composeToField.getText().trim();
        String subject = composeSubjectField.getText().trim();
        String body = composeBodyArea.getText();

        if (toStr.isEmpty()) {
            showAlert("Thiếu người nhận", "Vui lòng nhập ít nhất một người nhận vào trường 'Đến'.");
            return;
        }

        List<String> recipients = Arrays.stream(toStr.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> cc = Arrays.stream(composeCcField.getText().trim().split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> bcc = Arrays.stream(composeBccField.getText().trim().split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        btnSendMailAction.setDisable(true);
        composeUploadProgress.setVisible(true);
        composeUploadProgress.setManaged(true);
        composeUploadProgress.setProgress(-1);

        new Thread(() -> {
            List<Attachment> attachments = new ArrayList<>();
            // Upload attachments first via UDP
            for (File file : composeAttachedFiles) {
                boolean uploaded = service.uploadAttachment(file, null);
                if (uploaded) {
                    Attachment att = new Attachment(
                            UUID.randomUUID().toString(),
                            file.getName(),
                            file.length(),
                            Attachment.detectContentType(file.getName()),
                            service.getCurrentUsername(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    );
                    attachments.add(att);
                }
            }

            Email email = new Email(service.getCurrentUsername(), recipients, subject, body);
            email.setCc(cc);
            email.setBcc(bcc);
            email.setAttachments(attachments);
            email.setReplyToId(replyToId);

            boolean sent = service.sendMail(email);

            Platform.runLater(() -> {
                btnSendMailAction.setDisable(false);
                composeUploadProgress.setVisible(false);
                composeUploadProgress.setManaged(false);

                if (sent) {
                    hideComposeOverlay();
                    showMailListView();
                    if (currentFolder == MailFolder.SENT) {
                        onRefreshCurrentFolder();
                    }
                } else {
                    showAlert("Gửi thư thất bại", "Máy chủ không thể gửi thư này qua mạng UDP. Hãy kiểm tra kết nối.");
                }
            });
        }).start();
    }

    @FXML
    private void onSaveDraft() {
        String toStr = composeToField.getText().trim();
        List<String> recipients = toStr.isEmpty() ? Collections.emptyList()
                : Arrays.stream(toStr.split("[,;\\s]+")).map(String::trim).toList();

        Email draft = new Email(service.getCurrentUsername(), recipients,
                composeSubjectField.getText().trim(), composeBodyArea.getText());

        new Thread(() -> {
            service.saveDraft(draft);
            Platform.runLater(() -> {
                hideComposeOverlay();
                showMailListView();
                if (currentFolder == MailFolder.DRAFTS) {
                    onRefreshCurrentFolder();
                }
            });
        }).start();
    }

    private void showComposeOverlay() {
        composeOverlay.setVisible(true);
        composeOverlay.setManaged(true);
    }

    private void hideComposeOverlay() {
        composeOverlay.setVisible(false);
        composeOverlay.setManaged(false);
    }

    // =========================================================================
    // SEARCH MAIL
    // =========================================================================
    @FXML
    private void onSearchMail() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) {
            onClearSearch();
            return;
        }

        btnClearSearch.setVisible(true);
        btnClearSearch.setManaged(true);
        currentFolderTitle.setText("Kết quả tìm kiếm: '" + keyword + "'");
        showMailListView();

        new Thread(() -> {
            List<Email> results = service.searchMail(keyword);
            Platform.runLater(() -> {
                pageInfoLabel.setText("1 / 1");
                btnPrevPage.setDisable(true);
                btnNextPage.setDisable(true);
                renderMailList(results);
            });
        }).start();
    }

    @FXML
    private void onClearSearch() {
        searchField.clear();
        btnClearSearch.setVisible(false);
        btnClearSearch.setManaged(false);
        currentFolderTitle.setText(currentFolder.getDisplayName());
        showMailListView();
        loadMails(currentFolder, 1);
    }

    // =========================================================================
    // REAL-TIME NEW MAIL NOTIFICATION PUSH
    // =========================================================================
    private void handleNewMailPush(String sender, String subject, String mailId) {
        Platform.runLater(() -> {
            appendLog("[THƯ MỚI]: Từ " + sender + " - '" + subject + "'");
            if (currentFolder == MailFolder.INBOX) {
                loadMails(MailFolder.INBOX, currentPage);
            } else {
                new Thread(() -> {
                    ClientService.MailListResult res = service.listMails(MailFolder.INBOX, 1, 1);
                    Platform.runLater(() -> {
                        if (res.unreadInbox > 0) {
                            inboxBadgeCount.setText(String.valueOf(res.unreadInbox));
                            inboxBadgeCount.setVisible(true);
                            inboxBadgeCount.setManaged(true);
                        }
                    });
                }).start();
            }
        });
    }

    // =========================================================================
    // LOG DRAWER & HELPERS
    // =========================================================================
    @FXML
    private void onToggleLogDrawer() {
        boolean vis = !logDrawer.isVisible();
        logDrawer.setVisible(vis);
        logDrawer.setManaged(vis);
        btnToggleLog.setText(vis ? "✕ Đóng Nhật ký" : "Nhật ký UDP");
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
    }

    @FXML
    private void onLogout() {
        if (service != null) {
            service.logout();
        }
        showAuthScreen();
    }

    private void appendLog(String msg) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText("[" + time + "] " + msg + "\n");
            }
        });
    }

    private void showConnectScreen() {
        connectScreen.setVisible(true);
        connectScreen.setManaged(true);
        authScreen.setVisible(false);
        authScreen.setManaged(false);
        mainMailScreen.setVisible(false);
        mainMailScreen.setManaged(false);
    }

    private void showAuthScreen() {
        connectScreen.setVisible(false);
        connectScreen.setManaged(false);
        authScreen.setVisible(true);
        authScreen.setManaged(true);
        mainMailScreen.setVisible(false);
        mainMailScreen.setManaged(false);
    }

    private void showMainMailScreen() {
        connectScreen.setVisible(false);
        connectScreen.setManaged(false);
        authScreen.setVisible(false);
        authScreen.setManaged(false);
        mainMailScreen.setVisible(true);
        mainMailScreen.setManaged(true);
    }

    private void copyToClipboard(String text, Button btn, String originalText) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);

        btn.setText("Đã copy!");
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> btn.setText(originalText));
        }).start();
    }

    private String formatShortTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.length() < 16) return "";
        try {
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            if (dateTimeStr.startsWith(today)) {
                return dateTimeStr.substring(11, 16); // "HH:mm"
            } else {
                return dateTimeStr.substring(5, 10); // "MM-dd"
            }
        } catch (Exception e) {
            return dateTimeStr;
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public ClientService getService() {
        return service;
    }
}
