package com.udpchat.client.controller;

import com.udpchat.client.service.ClientService;
import com.udpchat.client.util.ImageHelper;
import com.udpchat.shared.util.UDPUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Điều khiển giao diện người dùng UDP Client với 3 màn hình chuyên nghiệp:
 * 1. Màn hình Kết nối Server (Connection Gateway)
 * 2. Màn hình Xác thực: Đăng nhập / Đăng ký (Auth Screen)
 * 3. Màn hình Chat thời gian thực (Zalo-style) với tệp tin &amp; ảnh inline, cùng Panel Quản lý Kênh
 * 
 * Toàn bộ giao tiếp và truyền nhận dữ liệu cam kết 100% qua giao thức UDP.
 */
public class ClientController {

    // ==========================================
    // 3 MÀN HÌNH CHÍNH (PAGES)
    // ==========================================
    @FXML private VBox connectScreen;
    @FXML private VBox authScreen;
    @FXML private BorderPane mainChatScreen;

    // ==========================================
    // MÀN HÌNH 1: KẾT NỐI SERVER
    // ==========================================
    @FXML private TextField serverHostField;
    @FXML private TextField portField;
    @FXML private Label myIpLabel;
    @FXML private Button copyMyIpButton;
    @FXML private Label connectErrorLabel;
    @FXML private Button btnConnectServer;

    // ==========================================
    // MÀN HÌNH 2: XÁC THỰC (LOGIN / REGISTER)
    // ==========================================
    @FXML private Label authServerBadge;
    @FXML private Label authModeTitle;
    @FXML private Label authModeSubtitle;
    @FXML private Button tabLoginButton;
    @FXML private Button tabRegisterButton;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label authStatusLabel;
    @FXML private Button authActionButton;

    // ==========================================
    // MÀN HÌNH 3: SIDEBAR WORKSPACE
    // ==========================================
    @FXML private Label avatarInitialLabel;
    @FXML private Label currentUsernameLabel;
    @FXML private Label currentServerLabel;
    @FXML private Button switchServerButton;
    @FXML private Button logoutButton;

    // ==========================================
    // MÀN HÌNH 3: KHUNG CHAT CHÍNH (ZALO STYLE)
    // ==========================================
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField messageField;
    @FXML private Button sendButton;

    // ==========================================
    // MÀN HÌNH 3: PANEL QUẢN LÝ KÊNH (DRAWER)
    // ==========================================
    @FXML private VBox channelMediaPanel;
    @FXML private FlowPane imagesGalleryPane;
    @FXML private Label noImagesLabel;
    @FXML private VBox filesListPane;
    @FXML private Label noFilesLabel;
    @FXML private TextArea logArea;

    // Logic fields
    private ClientService service;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private boolean isRegisterMode = false;

    private final Set<String> galleryImages = new HashSet<>();
    private final Set<String> galleryFiles = new HashSet<>();

    @FXML
    public void initialize() {
        serverHostField.setText("localhost");
        portField.setText("8888");
        myIpLabel.setText(UDPUtil.getLocalIPAddress());

        logArea.setEditable(false);

        // Tự động cuộn xuống dưới cùng khi có tin nhắn mới trong khung chat
        messagesBox.heightProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
        });

        showConnectScreen();
    }

    // ==========================================
    // ĐIỀU HƯỚNG MÀN HÌNH (SCREEN TRANSITIONS)
    // ==========================================
    private void setScreenVisible(Pane activeScreen) {
        connectScreen.setVisible(activeScreen == connectScreen);
        connectScreen.setManaged(activeScreen == connectScreen);

        authScreen.setVisible(activeScreen == authScreen);
        authScreen.setManaged(activeScreen == authScreen);

        mainChatScreen.setVisible(activeScreen == mainChatScreen);
        mainChatScreen.setManaged(activeScreen == mainChatScreen);
    }

    private void showConnectScreen() {
        setScreenVisible(connectScreen);
        connectErrorLabel.setVisible(false);
        connectErrorLabel.setManaged(false);
    }

    private void showAuthScreen() {
        setScreenVisible(authScreen);
        String host = getTargetHost();
        int port = getTargetPort();
        authServerBadge.setText(host + ":" + port);
        setAuthMode(false); // Mặc định là màn đăng nhập
        clearAuthFeedback();
    }

    private void showMainChatScreen() {
        setScreenVisible(mainChatScreen);
        String username = service != null && service.getCurrentUsername() != null 
                ? service.getCurrentUsername() : "Người dùng";
        currentUsernameLabel.setText(username);
        avatarInitialLabel.setText(username.isEmpty() ? "U" : username.substring(0, 1).toUpperCase());
        currentServerLabel.setText(getTargetHost() + ":" + getTargetPort());
        
        if (messagesBox.getChildren().isEmpty()) {
            addSystemMessage("Chào mừng " + username + " đã tham gia phòng trò chuyện UDP!");
            addSystemMessage("Mọi tin nhắn và tệp chia sẻ đều được gửi nhận qua giao thức UDP.");
        }
    }

    // ==========================================
    // MÀN HÌNH 1: LOGIC KẾT NỐI SERVER
    // ==========================================
    @FXML
    private void onUseDefaultPort() {
        portField.setText("8888");
    }

    @FXML
    private void onCopyMyIp() {
        String ip = myIpLabel.getText().trim();
        copyToClipboard(ip, copyMyIpButton, "Sao chép");
    }

    @FXML
    private void onConnectServer() {
        String host = getTargetHost();
        String portStr = portField.getText().trim();

        if (host.isEmpty() || portStr.isEmpty()) {
            showConnectError("Vui lòng nhập đầy đủ địa chỉ Host và Port của Server.");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                showConnectError("Cổng (Port) phải nằm trong khoảng từ 1 đến 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            showConnectError("Cổng (Port) không hợp lệ, vui lòng chỉ nhập số.");
            return;
        }

        btnConnectServer.setDisable(true);
        btnConnectServer.setText("Đang kiểm tra kết nối...");

        new Thread(() -> {
            try {
                InetAddress.getByName(host);

                if (service != null) {
                    service.close();
                }
                service = new ClientService(host, port);
                service.setOnLogMessage(this::addLogMessage);
                service.setOnIncomingStructuredMessage(this::handleIncomingStructuredMessage);

                Platform.runLater(() -> {
                    btnConnectServer.setDisable(false);
                    btnConnectServer.setText("Tiếp tục tới Đăng nhập →");
                    addLogMessage("Đã cấu hình địa chỉ Server UDP: " + host + ":" + port);
                    showAuthScreen();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnConnectServer.setDisable(false);
                    btnConnectServer.setText("Tiếp tục tới Đăng nhập →");
                    showConnectError("Không thể xác định địa chỉ máy chủ: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showConnectError(String msg) {
        connectErrorLabel.setText(msg);
        connectErrorLabel.setVisible(true);
        connectErrorLabel.setManaged(true);
    }

    private String getTargetHost() {
        String host = serverHostField.getText().trim();
        return host.isEmpty() ? "localhost" : host;
    }

    private int getTargetPort() {
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (Exception e) {
            return 8888;
        }
    }

    // ==========================================
    // MÀN HÌNH 2: LOGIC ĐĂNG NHẬP / ĐĂNG KÝ
    // ==========================================
    @FXML
    private void onBackToConnect() {
        showConnectScreen();
    }

    @FXML
    private void onSelectLoginTab() {
        setAuthMode(false);
    }

    @FXML
    private void onSelectRegisterTab() {
        setAuthMode(true);
    }

    private void setAuthMode(boolean registerMode) {
        this.isRegisterMode = registerMode;
        clearAuthFeedback();

        if (registerMode) {
            tabLoginButton.getStyleClass().remove("tab-btn-active");
            if (!tabRegisterButton.getStyleClass().contains("tab-btn-active")) {
                tabRegisterButton.getStyleClass().add("tab-btn-active");
            }
            authModeTitle.setText("Đăng ký tài khoản mới");
            authModeSubtitle.setText("Tạo tài khoản để tham gia mạng trò chuyện UDP");
            authActionButton.setText("Đăng ký tài khoản");
        } else {
            tabRegisterButton.getStyleClass().remove("tab-btn-active");
            if (!tabLoginButton.getStyleClass().contains("tab-btn-active")) {
                tabLoginButton.getStyleClass().add("tab-btn-active");
            }
            authModeTitle.setText("Đăng nhập tài khoản");
            authModeSubtitle.setText("Nhập tài khoản để tham gia mạng trò chuyện UDP");
            authActionButton.setText("Đăng nhập ngay");
        }
    }

    @FXML
    private void onAuthAction() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showAuthFeedback(false, "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.");
            return;
        }

        authActionButton.setDisable(true);

        new Thread(() -> {
            try {
                if (service == null) {
                    service = new ClientService(getTargetHost(), getTargetPort());
                    service.setOnLogMessage(this::addLogMessage);
                    service.setOnIncomingStructuredMessage(this::handleIncomingStructuredMessage);
                }

                if (isRegisterMode) {
                    String resp = service.register(username, password);
                    Platform.runLater(() -> {
                        authActionButton.setDisable(false);
                        if (resp.startsWith("RESPONSE|OK")) {
                            showAuthFeedback(true, "Đăng ký thành công! Hãy chuyển sang tab Đăng nhập để vào hệ thống.");
                            setAuthMode(false);
                        } else {
                            String err = resp.contains("|") ? resp.substring(resp.indexOf('|') + 1) : resp;
                            showAuthFeedback(false, "Đăng ký thất bại: " + err);
                        }
                    });
                } else {
                    String resp = service.login(username, password);
                    Platform.runLater(() -> {
                        authActionButton.setDisable(false);
                        if (resp.startsWith("RESPONSE|OK")) {
                            passwordField.clear();
                            showMainChatScreen();
                            addLogMessage("Đăng nhập thành công với tài khoản: " + username);
                        } else {
                            String err = resp.contains("|") ? resp.substring(resp.indexOf('|') + 1) : resp;
                            showAuthFeedback(false, "Đăng nhập thất bại: " + err);
                        }
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    authActionButton.setDisable(false);
                    showAuthFeedback(false, "Lỗi kết nối máy chủ UDP: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showAuthFeedback(boolean isSuccess, String message) {
        authStatusLabel.setText(message);
        authStatusLabel.getStyleClass().removeAll("feedback-success", "feedback-error");
        authStatusLabel.getStyleClass().add(isSuccess ? "feedback-success" : "feedback-error");
        authStatusLabel.setVisible(true);
        authStatusLabel.setManaged(true);
    }

    private void clearAuthFeedback() {
        authStatusLabel.setText("");
        authStatusLabel.setVisible(false);
        authStatusLabel.setManaged(false);
    }

    // ==========================================
    // MÀN HÌNH 3: SIDEBAR & QUẢN LÝ PHÒNG CHAT
    // ==========================================
    @FXML
    private void onSwitchServerFromWorkspace() {
        if (service != null && service.isLoggedIn()) {
            new Thread(() -> service.logout()).start();
        }
        showConnectScreen();
    }

    @FXML
    private void onLogout() {
        if (service != null && service.isLoggedIn()) {
            new Thread(() -> {
                service.logout();
                Platform.runLater(this::showAuthScreen);
            }).start();
        } else {
            showAuthScreen();
        }
    }

    // ==========================================
    // MÀN HÌNH 3: GỬI / NHẬN TIN NHẮN (ZALO STYLE)
    // ==========================================
    @FXML
    private void onSendMessage() {
        String msg = messageField.getText().trim();
        if (msg.isEmpty()) return;

        new Thread(() -> {
            String resp = service.sendMessage(msg);
            if (resp.startsWith("RESPONSE|OK")) {
                String time = LocalTime.now().format(timeFormatter);
                Platform.runLater(() -> {
                    addTextMessage("Tôi (me)", time, msg, true);
                    messageField.clear();
                });
            } else {
                addLogMessage("Không thể gửi tin nhắn qua UDP: " + resp);
            }
        }).start();
    }

    @FXML
    private void onClearChat() {
        messagesBox.getChildren().clear();
        addSystemMessage("Đã xóa toàn bộ nội dung trò chuyện trên màn hình.");
    }

    private void handleIncomingStructuredMessage(String sender, String time, String content) {
        Platform.runLater(() -> {
            if (content.startsWith("[FILE]|")) {
                String[] parts = content.split("\\|", 4);
                if (parts.length >= 4) {
                    String filename = parts[1];
                    long fileSize = 0;
                    try {
                        fileSize = Long.parseLong(parts[2]);
                    } catch (Exception ignored) {}
                    String fileType = parts[3];
                    addFileMessage(sender, time, filename, fileSize, fileType, null, false);
                }
            } else {
                addTextMessage(sender, time, content, false);
            }
        });
    }

    /**
     * Hiển thị bong bóng tin nhắn văn bản thông thường
     */
    private void addTextMessage(String sender, String time, String text, boolean isMe) {
        HBox row = new HBox();
        row.setFillHeight(false);

        VBox bubble = new VBox(4);
        bubble.getStyleClass().add(isMe ? "message-bubble-me" : "message-bubble-other");
        bubble.setMaxWidth(560);

        HBox header = new HBox(8);
        Label senderLbl = new Label(sender);
        senderLbl.getStyleClass().add(isMe ? "bubble-sender-me" : "bubble-sender-other");
        Label timeLbl = new Label(time);
        timeLbl.getStyleClass().add("bubble-time");

        if (isMe) {
            row.setAlignment(Pos.CENTER_RIGHT);
            header.setAlignment(Pos.CENTER_RIGHT);
            header.getChildren().addAll(timeLbl, senderLbl);
        } else {
            row.setAlignment(Pos.CENTER_LEFT);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getChildren().addAll(senderLbl, timeLbl);
        }

        Label contentLbl = new Label(text);
        contentLbl.getStyleClass().add("bubble-text");
        contentLbl.setWrapText(true);

        bubble.getChildren().addAll(header, contentLbl);
        row.getChildren().add(bubble);
        messagesBox.getChildren().add(row);
    }

    /**
     * Hiển thị thông báo hệ thống ở giữa khung chat
     */
    private void addSystemMessage(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);
        row.setFillHeight(false);

        VBox bubble = new VBox(2);
        bubble.getStyleClass().add("message-bubble-system");

        Label contentLbl = new Label(text);
        contentLbl.getStyleClass().add("bubble-sender-system");
        contentLbl.setWrapText(true);

        bubble.getChildren().add(contentLbl);
        row.getChildren().add(bubble);
        messagesBox.getChildren().add(row);
    }

    // ==========================================
    // MÀN HÌNH 3: TRUYỀN TỆP TIN & HÌNH ẢNH (UDP)
    // ==========================================
    @FXML
    private void onAttachFile() {
        if (service == null || !service.isLoggedIn()) {
            addLogMessage("Vui lòng đăng nhập để gửi tệp.");
            return;
        }

        Window window = messagesBox.getScene().getWindow();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn tệp hoặc hình ảnh để gửi qua UDP");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tất cả tệp (*.*)", "*.*"),
                new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg, *.ico, *.gif, *.bmp, *.webp, *.tif)",
                        "*.png", "*.jpg", "*.jpeg", "*.jpe", "*.jfif", "*.ico", "*.cur", "*.gif", "*.bmp", "*.dib", "*.webp", "*.tif", "*.tiff"),
                new FileChooser.ExtensionFilter("Tài liệu (*.pdf, *.doc, *.docx, *.txt, *.md)",
                        "*.pdf", "*.doc", "*.docx", "*.txt", "*.md"),
                new FileChooser.ExtensionFilter("Tệp nén (*.zip, *.rar, *.7z)",
                        "*.zip", "*.rar", "*.7z", "*.tar", "*.gz")
        );
        File file = fileChooser.showOpenDialog(window);

        if (file != null && file.exists()) {
            String filename = file.getName();
            long fileSize = file.length();
            String fileType = determineFileType(filename);
            String time = LocalTime.now().format(timeFormatter);

            addSystemMessage("Đang tải lên tệp: " + filename + " (" + formatFileSize(fileSize) + ") qua UDP...");

            new Thread(() -> {
                String result = service.uploadFile(file, progress -> {
                    // Cập nhật tiến độ tải lên nếu cần
                });

                Platform.runLater(() -> {
                    if (result.startsWith("OK")) {
                        // Gửi thông điệp thông báo file cho toàn bộ phòng chat
                        String payload = "[FILE]|" + filename + "|" + fileSize + "|" + fileType;
                        new Thread(() -> service.sendMessage(payload)).start();

                        // Hiển thị bong bóng chat tệp tin của chính mình
                        addFileMessage("Tôi (me)", time, filename, fileSize, fileType, file, true);
                        addLogMessage("Đã gửi tệp thành công qua UDP: " + filename);
                    } else {
                        addSystemMessage("Lỗi gửi tệp '" + filename + "': " + result);
                        addLogMessage("Tải lên tệp thất bại: " + result);
                    }
                });
            }).start();
        }
    }

    /**
     * Tạo và chèn Card tệp tin / hình ảnh inline ngay trên dòng chat (Zalo-style)
     */
    private void addFileMessage(String sender, String time, String filename, long fileSize, String fileType, File localFile, boolean isMe) {
        HBox row = new HBox();
        row.setFillHeight(false);

        VBox bubble = new VBox(6);
        bubble.getStyleClass().add(isMe ? "message-bubble-me" : "message-bubble-other");
        bubble.setMaxWidth(580);

        // Header (Tên người gửi + Thời gian)
        HBox header = new HBox(8);
        Label senderLbl = new Label(sender);
        senderLbl.getStyleClass().add(isMe ? "bubble-sender-me" : "bubble-sender-other");
        Label timeLbl = new Label(time);
        timeLbl.getStyleClass().add("bubble-time");

        if (isMe) {
            row.setAlignment(Pos.CENTER_RIGHT);
            header.setAlignment(Pos.CENTER_RIGHT);
            header.getChildren().addAll(timeLbl, senderLbl);
        } else {
            row.setAlignment(Pos.CENTER_LEFT);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getChildren().addAll(senderLbl, timeLbl);
        }

        // Hộp chứa chi tiết Card tệp tin
        VBox fileCard = new VBox(8);
        fileCard.getStyleClass().add("file-card-box");

        // Dòng thông tin cơ bản: Badge + Tên file + Dung lượng
        HBox topInfo = new HBox(10);
        topInfo.setAlignment(Pos.CENTER_LEFT);

        Label badge = new Label(fileType);
        badge.getStyleClass().add("file-type-badge");

        VBox meta = new VBox(2);
        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-title-text");
        nameLbl.setWrapText(true);
        Label sizeLbl = new Label(formatFileSize(fileSize));
        sizeLbl.getStyleClass().add("file-size-text");
        meta.getChildren().addAll(nameLbl, sizeLbl);

        topInfo.getChildren().addAll(badge, meta);
        fileCard.getChildren().add(topInfo);

        // Khung hiển thị xem trước ảnh (nếu là IMAGE)
        ImageView inlineImageView = new ImageView();
        inlineImageView.setFitWidth(280);
        inlineImageView.setPreserveRatio(true);
        inlineImageView.setSmooth(true);
        inlineImageView.getStyleClass().add("image-preview-view");

        File cacheDir = new File("client-cache");
        File cachedFile = new File(cacheDir, filename);

        if ("IMAGE".equalsIgnoreCase(fileType)) {
            File existingImgFile = (localFile != null && localFile.exists()) ? localFile 
                    : (cachedFile.exists() && cachedFile.length() == fileSize ? cachedFile : null);

            if (existingImgFile != null) {
                try {
                    Image img = ImageHelper.loadImage(existingImgFile);
                    if (img != null && !img.isError()) {
                        inlineImageView.setImage(img);
                        final File srcFile = existingImgFile;
                        inlineImageView.setOnMouseClicked(e -> showImagePreviewDialog(filename, img, srcFile));
                        fileCard.getChildren().add(inlineImageView);
                        addToImagesGallery(filename, img, srcFile);
                    }
                } catch (Exception e) {
                    addLogMessage("Lỗi nạp ảnh xem trước: " + e.getMessage());
                }
            }
        }

        // Dòng tương tác: Nút tải về / Tiến trình / Trạng thái / Xem trước ảnh
        HBox actionRow = new HBox(8);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar cardProgress = new ProgressBar(0);
        cardProgress.setVisible(false);
        cardProgress.setManaged(false);
        cardProgress.setPrefWidth(100);

        Label statusLbl = new Label("");
        statusLbl.getStyleClass().add("caption-muted");

        Button actionBtn = new Button(isMe && localFile != null ? "Mở thư mục" : "Tải về máy");
        actionBtn.getStyleClass().add("button-secondary");

        actionBtn.setOnAction(e -> {
            if (isMe && localFile != null && localFile.exists()) {
                try {
                    Desktop.getDesktop().open(localFile.getParentFile());
                } catch (Exception ex) {
                    addLogMessage("Không thể mở thư mục: " + ex.getMessage());
                }
                return;
            }

            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Chọn nơi lưu tệp: " + filename);
            File saveDir = chooser.showDialog(actionBtn.getScene().getWindow());

            if (saveDir != null) {
                actionBtn.setDisable(true);
                cardProgress.setVisible(true);
                cardProgress.setManaged(true);
                statusLbl.setText("Đang tải UDP...");

                new Thread(() -> {
                    File targetSavedFile = new File(saveDir, filename);

                    // Nếu đã tải vào cache trước đó, sao chép ngay lập tức
                    if (cachedFile.exists() && cachedFile.length() == fileSize) {
                        try {
                            Files.copy(cachedFile.toPath(), targetSavedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Platform.runLater(() -> {
                                cardProgress.setProgress(1.0);
                                actionBtn.setDisable(false);
                                actionBtn.setText("Đã tải xong");
                                statusLbl.setText("✓ " + targetSavedFile.getName());
                                addLogMessage("Đã lưu tệp từ cache: " + targetSavedFile.getAbsolutePath());
                            });
                            return;
                        } catch (Exception ignored) {}
                    }

                    // Tải tệp tin qua giao thức UDP
                    service.downloadFile(filename, saveDir, progress -> {
                        Platform.runLater(() -> {
                            cardProgress.setProgress(progress);
                            statusLbl.setText(String.format("%d%%", (int)(progress * 100)));
                        });
                    });

                    Platform.runLater(() -> {
                        cardProgress.setProgress(1.0);
                        actionBtn.setDisable(false);
                        actionBtn.setText("Đã tải xong");
                        statusLbl.setText("✓ " + targetSavedFile.getName());
                        addLogMessage("Tải tệp UDP thành công: " + targetSavedFile.getAbsolutePath());

                        if ("IMAGE".equalsIgnoreCase(fileType) && targetSavedFile.exists()) {
                            try {
                                Image img = ImageHelper.loadImage(targetSavedFile);
                                if (img != null && !img.isError()) {
                                    inlineImageView.setImage(img);
                                    inlineImageView.setOnMouseClicked(ev -> showImagePreviewDialog(filename, img, targetSavedFile));
                                    if (!fileCard.getChildren().contains(inlineImageView)) {
                                        int insertIdx = Math.min(1, fileCard.getChildren().size());
                                        fileCard.getChildren().add(insertIdx, inlineImageView);
                                    }
                                    addToImagesGallery(filename, img, targetSavedFile);
                                }
                            } catch (Exception ex) {
                                addLogMessage("Lỗi load ảnh tải về: " + ex.getMessage());
                            }
                        }
                    });
                }).start();
            }
        });

        actionRow.getChildren().addAll(actionBtn, cardProgress, statusLbl);

        // Nút Xem trước riêng cho hình ảnh
        if ("IMAGE".equalsIgnoreCase(fileType)) {
            Button previewBtn = new Button("👁 Xem ảnh");
            previewBtn.getStyleClass().add("button-secondary");
            previewBtn.setOnAction(e -> {
                File targetImg = (localFile != null && localFile.exists()) ? localFile
                        : (cachedFile.exists() && cachedFile.length() == fileSize ? cachedFile : null);
                if (targetImg != null) {
                    Image img = ImageHelper.loadImage(targetImg);
                    if (img != null) {
                        showImagePreviewDialog(filename, img, targetImg);
                    }
                } else {
                    actionBtn.setDisable(true);
                    previewBtn.setDisable(true);
                    statusLbl.setText("Đang tải ảnh...");
                    new Thread(() -> {
                        cacheDir.mkdirs();
                        service.downloadFile(filename, cacheDir, null);
                        Platform.runLater(() -> {
                            actionBtn.setDisable(false);
                            previewBtn.setDisable(false);
                            statusLbl.setText("");
                            if (cachedFile.exists() && cachedFile.length() == fileSize) {
                                Image img = ImageHelper.loadImage(cachedFile);
                                if (img != null && !img.isError()) {
                                    inlineImageView.setImage(img);
                                    inlineImageView.setOnMouseClicked(ev -> showImagePreviewDialog(filename, img, cachedFile));
                                    if (!fileCard.getChildren().contains(inlineImageView)) {
                                        int insertIdx = Math.min(1, fileCard.getChildren().size());
                                        fileCard.getChildren().add(insertIdx, inlineImageView);
                                    }
                                    addToImagesGallery(filename, img, cachedFile);
                                    showImagePreviewDialog(filename, img, cachedFile);
                                }
                            }
                        });
                    }).start();
                }
            });
            actionRow.getChildren().add(previewBtn);
        }

        fileCard.getChildren().add(actionRow);

        // Tự động tải trước hình ảnh vào cache để xem preview inline (Zalo style)
        if (!isMe && "IMAGE".equalsIgnoreCase(fileType) && (!cachedFile.exists() || cachedFile.length() != fileSize)) {
            new Thread(() -> {
                cacheDir.mkdirs();
                service.downloadFile(filename, cacheDir, null);
                if (cachedFile.exists() && cachedFile.length() == fileSize) {
                    Platform.runLater(() -> {
                        try {
                            Image img = ImageHelper.loadImage(cachedFile);
                            if (img != null && !img.isError()) {
                                inlineImageView.setImage(img);
                                inlineImageView.setOnMouseClicked(ev -> showImagePreviewDialog(filename, img, cachedFile));
                                if (!fileCard.getChildren().contains(inlineImageView)) {
                                    int insertIdx = Math.min(1, fileCard.getChildren().size());
                                    fileCard.getChildren().add(insertIdx, inlineImageView);
                                }
                                addToImagesGallery(filename, img, cachedFile);
                            }
                        } catch (Exception e) {
                            addLogMessage("Lỗi load ảnh cache: " + e.getMessage());
                        }
                    });
                }
            }).start();
        }

        bubble.getChildren().addAll(header, fileCard);
        row.getChildren().add(bubble);
        messagesBox.getChildren().add(row);

        // Thêm vào danh sách tệp của Quản lý Kênh (tách biệt tài liệu và hình ảnh)
        if (!"IMAGE".equalsIgnoreCase(fileType)) {
            addToFileListPane(filename, fileSize, fileType);
        }
    }

    // ==========================================
    // MÀN HÌNH 3: PANEL QUẢN LÝ KÊNH (MEDIA & TỆP)
    // ==========================================
    @FXML
    private void onToggleMediaPanel() {
        boolean visible = channelMediaPanel.isVisible();
        channelMediaPanel.setVisible(!visible);
        channelMediaPanel.setManaged(!visible);
    }

    @FXML
    private void onCloseMediaPanel() {
        channelMediaPanel.setVisible(false);
        channelMediaPanel.setManaged(false);
    }

    private void addToImagesGallery(String filename, Image img, File sourceFile) {
        if (galleryImages.contains(filename)) return;
        galleryImages.add(filename);

        Platform.runLater(() -> {
            noImagesLabel.setVisible(false);
            noImagesLabel.setManaged(false);

            StackPane thumbCard = new StackPane();
            thumbCard.getStyleClass().add("gallery-item");
            thumbCard.setPrefSize(72, 72);
            thumbCard.setMaxSize(72, 72);

            ImageView thumbView = new ImageView(img);
            thumbView.setFitWidth(68);
            thumbView.setFitHeight(68);
            thumbView.setPreserveRatio(true);
            thumbCard.getChildren().add(thumbView);

            Tooltip.install(thumbCard, new Tooltip(filename));
            thumbCard.setOnMouseClicked(e -> showImagePreviewDialog(filename, img, sourceFile));

            imagesGalleryPane.getChildren().add(thumbCard);
        });
    }

    private void addToFileListPane(String filename, long fileSize, String fileType) {
        if (galleryFiles.contains(filename)) return;
        galleryFiles.add(filename);

        Platform.runLater(() -> {
            noFilesLabel.setVisible(false);
            noFilesLabel.setManaged(false);

            HBox item = new HBox(8);
            item.getStyleClass().add("file-list-item");
            item.setAlignment(Pos.CENTER_LEFT);

            Label badge = new Label(fileType);
            badge.getStyleClass().add("file-type-badge");

            VBox info = new VBox(2);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label nameLbl = new Label(filename);
            nameLbl.getStyleClass().add("file-title-text");
            nameLbl.setStyle("-fx-font-size: 11.5px;");
            nameLbl.setMaxWidth(140);
            nameLbl.setWrapText(false);

            Label sizeLbl = new Label(formatFileSize(fileSize));
            sizeLbl.getStyleClass().add("caption-muted");
            info.getChildren().addAll(nameLbl, sizeLbl);

            Button dlBtn = new Button("Tải");
            dlBtn.getStyleClass().add("button-secondary");
            dlBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3px 8px;");

            dlBtn.setOnAction(e -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Chọn nơi lưu: " + filename);
                File dir = chooser.showDialog(dlBtn.getScene().getWindow());
                if (dir != null) {
                    dlBtn.setDisable(true);
                    new Thread(() -> {
                        service.downloadFile(filename, dir, null);
                        Platform.runLater(() -> {
                            dlBtn.setDisable(false);
                            dlBtn.setText("✓");
                            addLogMessage("Tải tệp thành công từ Quản lý Kênh qua UDP: " + filename);
                        });
                    }).start();
                }
            });

            item.getChildren().addAll(badge, info, dlBtn);
            filesListPane.getChildren().add(item);
        });
    }

    /**
     * Mở cửa sổ xem trước ảnh tương tác cao (Zalo/Telegram-style LightBox Modal)
     */
    private void showImagePreviewDialog(String filename, Image img, File sourceFile) {
        if (img == null) return;
        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                stage.setTitle("Xem trước ảnh: " + filename);
                if (messagesBox.getScene() != null && messagesBox.getScene().getWindow() != null) {
                    stage.initOwner(messagesBox.getScene().getWindow());
                }
                stage.initModality(Modality.NONE);

                BorderPane root = new BorderPane();
                root.setStyle("-fx-background-color: #070A10;");

                // --- TOP BAR: THÔNG TIN ẢNH & CÁC NÚT ĐIỀU KHIỂN ---
                HBox topBar = new HBox(12);
                topBar.setAlignment(Pos.CENTER_LEFT);
                topBar.setStyle("-fx-background-color: #0D131F; -fx-padding: 10px 16px; -fx-border-color: #1E293B; -fx-border-width: 0 0 1px 0;");

                Label formatBadge = new Label(ImageHelper.getImageFormatName(filename));
                formatBadge.getStyleClass().add("file-type-badge");

                VBox titleBox = new VBox(2);
                Label nameLbl = new Label(filename);
                nameLbl.setStyle("-fx-font-family: 'Segoe UI', Arial; -fx-font-weight: bold; -fx-text-fill: #F8FAFC; -fx-font-size: 13px;");

                String dims = (int) img.getWidth() + " × " + (int) img.getHeight() + " px";
                String sizeStr = sourceFile != null && sourceFile.exists() ? " • " + formatFileSize(sourceFile.length()) : "";
                Label infoLbl = new Label(dims + sizeStr);
                infoLbl.setStyle("-fx-font-family: 'Segoe UI', Arial; -fx-text-fill: #94A3B8; -fx-font-size: 11px;");
                titleBox.getChildren().addAll(nameLbl, infoLbl);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                // Các nút công cụ Zoom
                HBox tools = new HBox(8);
                tools.setAlignment(Pos.CENTER_RIGHT);

                Button btnZoomOut = new Button("−");
                btnZoomOut.getStyleClass().add("button-secondary");
                btnZoomOut.setStyle("-fx-font-size: 13px; -fx-padding: 3px 10px;");

                Label zoomLbl = new Label("100%");
                zoomLbl.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #F59E0B; -fx-font-weight: bold; -fx-min-width: 48px; -fx-alignment: center;");

                Button btnZoomIn = new Button("+");
                btnZoomIn.getStyleClass().add("button-secondary");
                btnZoomIn.setStyle("-fx-font-size: 13px; -fx-padding: 3px 10px;");

                Button btnActualSize = new Button("1:1 Gốc");
                btnActualSize.getStyleClass().add("button-secondary");
                btnActualSize.setStyle("-fx-font-size: 11px; -fx-padding: 4px 8px;");

                Button btnFit = new Button("Vừa khung");
                btnFit.getStyleClass().add("button-secondary");
                btnFit.setStyle("-fx-font-size: 11px; -fx-padding: 4px 8px;");

                tools.getChildren().addAll(btnZoomOut, zoomLbl, btnZoomIn, btnActualSize, btnFit);

                if (sourceFile != null && sourceFile.exists()) {
                    Button btnOpenSys = new Button("Mở ngoài ↗");
                    btnOpenSys.getStyleClass().add("button-secondary");
                    btnOpenSys.setStyle("-fx-font-size: 11px; -fx-padding: 4px 8px;");
                    btnOpenSys.setOnAction(ev -> {
                        try {
                            Desktop.getDesktop().open(sourceFile);
                        } catch (Exception ex) {
                            addLogMessage("Không thể mở bằng ứng dụng hệ thống: " + ex.getMessage());
                        }
                    });
                    tools.getChildren().add(btnOpenSys);
                }

                Button btnSaveAs = new Button("Lưu ảnh...");
                btnSaveAs.getStyleClass().add("button-secondary");
                btnSaveAs.setStyle("-fx-font-size: 11px; -fx-padding: 4px 8px;");
                btnSaveAs.setOnAction(ev -> {
                    FileChooser saveChooser = new FileChooser();
                    saveChooser.setTitle("Lưu ảnh về máy");
                    saveChooser.setInitialFileName(filename);
                    File dest = saveChooser.showSaveDialog(stage);
                    if (dest != null) {
                        new Thread(() -> {
                            try {
                                if (sourceFile != null && sourceFile.exists()) {
                                    Files.copy(sourceFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    addLogMessage("Đã lưu ảnh thành công: " + dest.getAbsolutePath());
                                } else {
                                    service.downloadFile(filename, dest.getParentFile(), null);
                                    addLogMessage("Đã tải và lưu ảnh: " + dest.getAbsolutePath());
                                }
                            } catch (Exception ex) {
                                addLogMessage("Lỗi lưu ảnh: " + ex.getMessage());
                            }
                        }).start();
                    }
                });
                tools.getChildren().add(btnSaveAs);

                Button btnClose = new Button("✕");
                btnClose.getStyleClass().add("button-secondary");
                btnClose.setStyle("-fx-font-size: 12px; -fx-padding: 4px 10px;");
                btnClose.setOnAction(ev -> stage.close());

                topBar.getChildren().addAll(formatBadge, titleBox, spacer, tools, btnClose);
                root.setTop(topBar);

                // --- CENTER: KHUNG HIỂN THỊ ẢNH VÀ THU PHÓNG ---
                ImageView previewView = new ImageView(img);
                previewView.setPreserveRatio(true);
                previewView.setSmooth(true);

                StackPane imageContainer = new StackPane(previewView);
                imageContainer.setStyle("-fx-background-color: #070A10; -fx-padding: 20px;");
                imageContainer.setAlignment(Pos.CENTER);

                ScrollPane scrollPane = new ScrollPane(imageContainer);
                scrollPane.setStyle("-fx-background-color: #070A10; -fx-background: #070A10; -fx-border-color: transparent;");
                scrollPane.setFitToWidth(true);
                scrollPane.setFitToHeight(true);

                double naturalW = img.getWidth() > 0 ? img.getWidth() : 400;
                double naturalH = img.getHeight() > 0 ? img.getHeight() : 300;
                double initFitW = Math.min(naturalW, 850);
                double initFitH = Math.min(naturalH, 600);
                if (initFitW < 320) initFitW = 320;
                if (initFitH < 220) initFitH = 220;

                final double[] scale = { 1.0 };

                Runnable applyZoom = () -> {
                    previewView.setFitWidth(naturalW * scale[0]);
                    previewView.setFitHeight(naturalH * scale[0]);
                    zoomLbl.setText(String.format("%d%%", (int)(scale[0] * 100)));
                };

                double initialScale = Math.min(initFitW / naturalW, initFitH / naturalH);
                if (initialScale > 1.0) initialScale = 1.0;
                scale[0] = initialScale;
                applyZoom.run();

                btnZoomIn.setOnAction(ev -> {
                    scale[0] = Math.min(scale[0] * 1.25, 5.0);
                    applyZoom.run();
                });

                btnZoomOut.setOnAction(ev -> {
                    scale[0] = Math.max(scale[0] / 1.25, 0.1);
                    applyZoom.run();
                });

                btnActualSize.setOnAction(ev -> {
                    scale[0] = 1.0;
                    applyZoom.run();
                });

                btnFit.setOnAction(ev -> {
                    double wAvail = scrollPane.getViewportBounds().getWidth() - 40;
                    double hAvail = scrollPane.getViewportBounds().getHeight() - 40;
                    if (wAvail > 50 && hAvail > 50) {
                        scale[0] = Math.min(wAvail / naturalW, hAvail / naturalH);
                        applyZoom.run();
                    }
                });

                scrollPane.setOnScroll(ev -> {
                    ev.consume();
                    if (ev.getDeltaY() > 0) {
                        scale[0] = Math.min(scale[0] * 1.15, 5.0);
                    } else if (ev.getDeltaY() < 0) {
                        scale[0] = Math.max(scale[0] / 1.15, 0.1);
                    }
                    applyZoom.run();
                });

                root.setCenter(scrollPane);

                Scene scene = new Scene(root, Math.max(initFitW + 80, 720), Math.max(initFitH + 110, 520));
                scene.setOnKeyPressed(ke -> {
                    if (ke.getCode() == KeyCode.ESCAPE) {
                        stage.close();
                    }
                });

                stage.setScene(scene);
                stage.show();
            } catch (Exception e) {
                addLogMessage("Không thể mở cửa sổ xem trước ảnh: " + e.getMessage());
            }
        });
    }

    // ==========================================
    // NHẬT KÝ VÀ TIỆN ÍCH
    // ==========================================
    @FXML
    private void onClearLog() {
        logArea.clear();
        addLogMessage("Đã xóa nhật ký hiển thị trên màn hình.");
    }

    private void addLogMessage(String message) {
        Platform.runLater(() -> {
            String time = LocalTime.now().format(timeFormatter);
            logArea.appendText("[" + time + "] " + message + "\n");
        });
    }

    private String determineFileType(String filename) {
        if (ImageHelper.isImageExtension(filename)) {
            return "IMAGE";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        } else if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "DOC";
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return "ZIP";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov")) {
            return "VIDEO";
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")) {
            return "AUDIO";
        }
        return "FILE";
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void copyToClipboard(String text, Button btn, String originalText) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);

        btn.setText("Đã sao chép!");
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> btn.setText(originalText));
        }).start();
    }

    public ClientService getService() {
        return service;
    }
}
