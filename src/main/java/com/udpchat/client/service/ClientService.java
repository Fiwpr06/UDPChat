package com.udpchat.client.service;

import com.udpchat.client.network.ClientListener;
import com.udpchat.client.network.ClientNetwork;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.protocol.UDPConstants;
import com.udpchat.shared.util.JsonUtil;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tầng nghiệp vụ xử lý các thao tác Thư điện tử qua mạng UDP cho Client
 */
public class ClientService {
    private ClientNetwork network;
    private ClientListener listener;
    private Thread listenerThread;
    private String currentUsername;

    @FunctionalInterface
    public interface NewMailConsumer {
        void accept(String sender, String subject, String mailId);
    }

    public static class MailListResult {
        public final List<Email> emails;
        public final int total;
        public final int unreadInbox;

        public MailListResult(List<Email> emails, int total, int unreadInbox) {
            this.emails = emails != null ? emails : new ArrayList<>();
            this.total = total;
            this.unreadInbox = unreadInbox;
        }
    }

    private NewMailConsumer onNewMailNotification;
    private Consumer<String> onLogMessage;

    private String serverHost;
    private int serverPort;

    public ClientService(String serverHost, int serverPort) throws Exception {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.network = new ClientNetwork(InetAddress.getByName(serverHost), serverPort);
    }

    public void setOnNewMailNotification(NewMailConsumer onNewMailNotification) {
        this.onNewMailNotification = onNewMailNotification;
    }

    public void setOnLogMessage(Consumer<String> onLogMessage) {
        this.onLogMessage = onLogMessage;
    }

    private void log(String msg) {
        if (onLogMessage != null) {
            onLogMessage.accept(msg);
        }
    }

    private void handlePushMessage(String rawMessage) {
        Command cmd = ProtocolHelper.parseCommand(rawMessage);
        if (cmd == Command.NEW_MAIL) {
            // Định dạng: NEW_MAIL|sender|subject|mailId
            String[] parts = ProtocolHelper.parseParams(rawMessage);
            if (parts.length >= 3) {
                String sender = parts[0];
                String subject = parts[1];
                String mailId = parts[2];
                log("Nhận thông báo thư mới từ [" + sender + "]: '" + subject + "'");
                if (onNewMailNotification != null) {
                    onNewMailNotification.accept(sender, subject, mailId);
                }
            }
        }
    }

    public String register(String username, String password) {
        try {
            String request = ProtocolHelper.buildRequest(Command.REGISTER, username, password);
            String response = network.sendRequest(request);
            if (response.startsWith("RESPONSE|OK")) {
                log("Đăng ký thành công tài khoản '" + username + "'");
            } else {
                log("Đăng ký không thành công: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi đăng ký: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String login(String username, String password) {
        try {
            listener = new ClientListener(this::handlePushMessage);
            int listenerPort = listener.getPort();

            String request = ProtocolHelper.buildRequest(Command.LOGIN, username, password, String.valueOf(listenerPort));
            String response = network.sendRequest(request);

            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                currentUsername = username;
                listenerThread = new Thread(listener);
                listenerThread.setDaemon(true);
                listenerThread.start();
                log("Đăng nhập thành công! Hộp thư của '" + username + "' đã sẵn sàng.");
            } else {
                listener.stop();
                log("Đăng nhập thất bại: " + response);
            }
            return response;
        } catch (Exception e) {
            log("Lỗi kết nối khi đăng nhập: " + e.getMessage());
            if (listener != null) listener.stop();
            return "ERROR|Exception: " + e.getMessage();
        }
    }

    public String logout() {
        if (!isLoggedIn()) return "OK|Đã đăng xuất trước đó";
        try {
            String request = ProtocolHelper.buildRequest(Command.LOGOUT);
            String response = network.sendRequest(request);
            log("Đã đăng xuất khỏi máy chủ thư.");
            return response;
        } catch (Exception e) {
            log("Lỗi đăng xuất: " + e.getMessage());
            return "ERROR|Exception: " + e.getMessage();
        } finally {
            currentUsername = null;
            if (listener != null) {
                listener.stop();
            }
        }
    }

    /**
     * Gửi email mới qua UDP
     */
    public boolean sendMail(Email email) {
        if (!isLoggedIn()) return false;
        try {
            email.setSender(currentUsername);
            String request = ProtocolHelper.buildSendMailRequest(email);
            log("Đang gửi thư: '" + email.getSubject() + "' tới " + email.getRecipientsDisplay() + "...");
            String response = network.sendRequest(request);
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                log("Gửi thư thành công! (ID: " + email.getId() + ")");
                return true;
            } else {
                log("Gửi thư thất bại: " + response);
                return false;
            }
        } catch (Exception e) {
            log("Lỗi gửi thư: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy danh sách email theo thư mục kèm phân trang
     */
    public MailListResult listMails(MailFolder folder, int page, int pageSize) {
        if (!isLoggedIn()) return new MailListResult(Collections.emptyList(), 0, 0);
        try {
            String request = ProtocolHelper.buildListMailRequest(folder, page, pageSize);
            String response = network.sendRequest(request);
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                if (parts.length >= 3) {
                    String base64Json = parts[2];
                    String json = JsonUtil.decodeBase64(base64Json);
                    List<Email> list = JsonUtil.emailListFromJson(json);
                    int total = parts.length >= 4 ? Integer.parseInt(parts[3]) : list.size();
                    int unread = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
                    return new MailListResult(list, total, unread);
                }
            }
        } catch (Exception e) {
            log("Lỗi lấy danh sách thư (" + folder + "): " + e.getMessage());
        }
        return new MailListResult(Collections.emptyList(), 0, 0);
    }

    /**
     * Đọc chi tiết một email theo ID
     */
    public Email readMail(String mailId) {
        if (!isLoggedIn()) return null;
        try {
            String request = ProtocolHelper.buildReadMailRequest(mailId);
            String response = network.sendRequest(request);
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                if (parts.length >= 3) {
                    String base64 = parts[2];
                    String json = JsonUtil.decodeBase64(base64);
                    return JsonUtil.fromJson(json, Email.class);
                }
            }
        } catch (Exception e) {
            log("Lỗi đọc thư: " + e.getMessage());
        }
        return null;
    }

    /**
     * Xóa email (vào Thùng rác hoặc xóa vĩnh viễn)
     */
    public boolean deleteMail(String mailId, boolean permanent) {
        if (!isLoggedIn()) return false;
        try {
            String request = ProtocolHelper.buildDeleteMailRequest(mailId, permanent);
            String response = network.sendRequest(request);
            boolean ok = response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK");
            if (ok) {
                log(permanent ? "Đã xóa vĩnh viễn thư." : "Đã chuyển thư vào Thùng rác.");
            }
            return ok;
        } catch (Exception e) {
            log("Lỗi xóa thư: " + e.getMessage());
            return false;
        }
    }

    /**
     * Đánh dấu hoặc bỏ gắn sao
     */
    public boolean toggleStar(String mailId, boolean starred) {
        if (!isLoggedIn()) return false;
        try {
            String request = ProtocolHelper.buildStarMailRequest(mailId, starred);
            String response = network.sendRequest(request);
            return response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK");
        } catch (Exception e) {
            log("Lỗi cập nhật dấu sao: " + e.getMessage());
            return false;
        }
    }

    /**
     * Đánh dấu đã đọc / chưa đọc
     */
    public boolean markRead(String mailId, boolean read) {
        if (!isLoggedIn()) return false;
        try {
            String request = ProtocolHelper.buildMarkReadRequest(mailId, read);
            String response = network.sendRequest(request);
            return response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK");
        } catch (Exception e) {
            log("Lỗi đánh dấu trạng thái đọc: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lưu bản nháp (Draft)
     */
    public boolean saveDraft(Email draft) {
        if (!isLoggedIn()) return false;
        try {
            draft.setSender(currentUsername);
            String request = ProtocolHelper.buildSaveDraftRequest(draft);
            String response = network.sendRequest(request);
            boolean ok = response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK");
            if (ok) {
                log("Đã lưu bản nháp.");
            }
            return ok;
        } catch (Exception e) {
            log("Lỗi lưu nháp: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tìm kiếm thư điện tử theo từ khóa
     */
    public List<Email> searchMail(String keyword) {
        if (!isLoggedIn() || keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String request = ProtocolHelper.buildSearchMailRequest(keyword);
            String response = network.sendRequest(request);
            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                if (parts.length >= 3) {
                    String base64 = parts[2];
                    String json = JsonUtil.decodeBase64(base64);
                    return JsonUtil.emailListFromJson(json);
                }
            }
        } catch (Exception e) {
            log("Lỗi tìm kiếm thư: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Tải lên tệp đính kèm qua UDP socket riêng biệt
     */
    public boolean uploadAttachment(File file, Consumer<Double> onProgress) {
        if (!isLoggedIn()) return false;
        try {
            int totalChunks = (int) Math.ceil((double) file.length() / UDPConstants.CHUNK_DATA_SIZE);
            if (totalChunks == 0) totalChunks = 1;
            String request = ProtocolHelper.buildRequest(Command.UPLOAD, file.getName(), String.valueOf(totalChunks));
            log("Đang tải lên tệp đính kèm '" + file.getName() + "' (" + file.length() + " bytes)...");
            String response = network.sendRequest(request);

            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] respParts = response.split("\\" + UDPConstants.DELIMITER);
                int filePort = network.getServerPort();
                if (respParts.length >= 3) {
                    try {
                        filePort = Integer.parseInt(respParts[2]);
                    } catch (NumberFormatException ignored) {}
                }
                network.sendFileChunks(file, network.getServerAddress(), filePort, onProgress);
                log("Tải lên tệp đính kèm '" + file.getName() + "' hoàn tất!");
                return true;
            } else {
                log("Máy chủ từ chối tải tệp đính kèm: " + response);
            }
            return false;
        } catch (Exception e) {
            log("Lỗi tải tệp đính kèm: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tải xuống tệp đính kèm qua UDP socket riêng biệt
     */
    public boolean downloadAttachment(String filename, File saveLocation, Consumer<Double> onProgress) {
        if (!isLoggedIn()) return false;
        try {
            String request = ProtocolHelper.buildRequest(Command.DOWNLOAD, filename);
            log("Yêu cầu tải xuống tệp đính kèm '" + filename + "'...");
            String response = network.sendRequest(request);

            if (response.startsWith(Command.RESPONSE.name() + UDPConstants.DELIMITER + "OK")) {
                String[] parts = response.split("\\" + UDPConstants.DELIMITER);
                int totalChunks = Integer.parseInt(parts[2]);

                int filePort = network.getServerPort();
                if (parts.length >= 4) {
                    try {
                        filePort = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {}
                }

                File outputFile = new File(saveLocation, filename);
                log("Đang nhận " + totalChunks + " phần dữ liệu của tệp '" + filename + "'...");
                network.receiveFileChunks(totalChunks, outputFile, network.getServerAddress(), filePort, onProgress);
                log("Tải tệp đính kèm thành công! Lưu tại: " + outputFile.getAbsolutePath());
                return true;
            } else {
                log("Tải tệp không thành công: " + response);
            }
            return false;
        } catch (Exception e) {
            log("Lỗi tải tệp đính kèm: " + e.getMessage());
            return false;
        }
    }

    public boolean isLoggedIn() {
        return currentUsername != null;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void close() {
        if (listener != null) {
            listener.stop();
        }
        if (network != null) {
            network.close();
        }
    }
}
