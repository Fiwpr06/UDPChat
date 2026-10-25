package com.udpchat.server.network;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MailService;
import com.udpchat.server.service.OAuthService;
import com.udpchat.server.service.SmtpDeliveryService;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.util.JsonUtil;
import com.udpchat.shared.util.UDPUtil;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Xử lý các yêu cầu giao thức mạng UDP cho Mail Server
 */
public class RequestHandler {
    private final AuthService authService;
    private final MailService mailService;
    private final FileService fileService;
    private final OAuthService oAuthService;
    private final SmtpDeliveryService smtpDeliveryService;
    private final Consumer<String> logger;

    public RequestHandler(AuthService authService, MailService mailService, FileService fileService, Consumer<String> logger) {
        this(authService, mailService, fileService, null, null, logger);
    }

    public RequestHandler(AuthService authService, MailService mailService, FileService fileService, OAuthService oAuthService, SmtpDeliveryService smtpDeliveryService, Consumer<String> logger) {
        this.authService = authService;
        this.mailService = mailService;
        this.fileService = fileService;
        this.oAuthService = oAuthService;
        this.smtpDeliveryService = smtpDeliveryService;
        this.logger = logger != null ? logger : System.out::println;
    }

    public void handle(String rawMessage, InetAddress senderAddr, int senderPort, DatagramSocket serverSocket) {
        try {
            Command cmd = ProtocolHelper.parseCommand(rawMessage);
            String[] params = ProtocolHelper.parseParams(rawMessage);
            String sessionKey = AuthService.makeSessionKey(senderAddr, senderPort);

            if (cmd == null) return;

            switch (cmd) {
                case REGISTER: {
                    String username = params.length > 0 ? params[0] : "";
                    String password = params.length > 1 ? params[1] : "";
                    String email = params.length > 2 ? params[2] : null;
                    String regStatus = authService.register(username, password, senderAddr != null ? AuthService.normalizeAddress(senderAddr) : "127.0.0.1", email);
                    boolean regOk = regStatus.contains("Thành công") || regStatus.startsWith("Success");
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(regOk ? "OK" : "ERROR", regStatus), senderAddr, senderPort);
                    logger.accept("Đăng ký tài khoản: " + username + " từ " + sessionKey + " -> " + (regOk ? "Thành công" : regStatus));
                    break;
                }
                case LOGIN: {
                    String username = params.length > 0 ? params[0] : "";
                    String password = params.length > 1 ? params[1] : "";
                    int listenerPort = params.length > 2 ? Integer.parseInt(params[2]) : senderPort;
                    String loginStatus = authService.login(username, password, senderAddr, senderPort, listenerPort);
                    boolean loginOk = loginStatus.contains("Thành công") || loginStatus.startsWith("Success");
                    String userEmail = loginOk ? authService.getUserEmail(username) : "";
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(loginOk ? "OK" : "ERROR", loginStatus, userEmail != null ? userEmail : ""), senderAddr, senderPort);
                    if (loginOk) {
                        logger.accept("Người dùng '" + username + "' đăng nhập thành công (Cổng nhận: " + listenerPort + ")");
                    } else {
                        logger.accept("Đăng nhập thất bại cho '" + username + "': " + loginStatus);
                    }
                    break;
                }
                case LOGOUT: {
                    String logoutUser = authService.getUsername(sessionKey);
                    authService.logout(sessionKey);
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK"), senderAddr, senderPort);
                    logger.accept("Người dùng '" + (logoutUser != null ? logoutUser : sessionKey) + "' đã đăng xuất.");
                    break;
                }
                case OAUTH_START: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String redirectUri = params.length > 0 ? params[0] : "http://127.0.0.1:8888/callback";
                        if (oAuthService != null && oAuthService.getConfig().isConfigured()) {
                            String authUrl = oAuthService.getConfig().buildAuthUrl(redirectUri, authService.getUsername(sessionKey));
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", authUrl), senderAddr, senderPort);
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Máy chủ chưa cấu hình Client ID / Secret trong client_secret.json"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case OAUTH_CALLBACK: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String authCode = params.length > 0 ? params[0] : "";
                        String redirectUri = params.length > 1 ? params[1] : "";
                        if (oAuthService != null) {
                            OAuthService.TokenResult result = oAuthService.exchangeCode(authCode, redirectUri);
                            if (result.success) {
                                oAuthService.saveTokens(user, result.email, result.accessToken, result.refreshToken, result.expiresIn);
                                logger.accept("[OAuth 2.0] Người dùng '" + user + "' đã liên kết thành công với Gmail: " + result.email);
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", result.email), senderAddr, senderPort);
                            } else {
                                logger.accept("[OAuth 2.0] Thất bại khi đổi token cho '" + user + "': " + result.errorMessage);
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", result.errorMessage), senderAddr, senderPort);
                            }
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Dịch vụ OAuth chưa sẵn sàng"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case OAUTH_STATUS: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        if (oAuthService != null && oAuthService.isLinked(user)) {
                            String email = oAuthService.getLinkedEmail(user);
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", "LINKED", email != null ? email : ""), senderAddr, senderPort);
                        } else {
                            String email = authService.getUserEmail(user);
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", "NOT_LINKED", email != null ? email : ""), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case SET_EMAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String email = params.length > 0 ? params[0].trim() : "";
                        boolean updated = authService.setUserEmail(user, email);
                        if (updated) {
                            logger.accept("Người dùng '" + user + "' cập nhật địa chỉ email: " + email);
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", email), senderAddr, senderPort);
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Không thể cập nhật email"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case GET_EMAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String email = authService.getUserEmail(user);
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", email != null ? email : ""), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case SEND_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String sender = authService.getUsername(sessionKey);
                        String emailJson = JsonUtil.decodeBase64(params[0]);
                        Email email = JsonUtil.fromJson(emailJson, Email.class);
                        if (email != null) {
                            String senderEmail = authService.getUserEmail(sender);
                            email.setSender(sender);
                            email.setSenderEmail(senderEmail);

                            // Tạo bản dùng riêng cho ứng dụng
                            Email appEmail = JsonUtil.fromJson(
                                    JsonUtil.toJson(email),
                                    Email.class
                            );

                            String internalText = "\n\n[Thông tin chỉ hiển thị trong UDPChat]";
                            appEmail.setBody(appEmail.getBody() + internalText);

                            // Lưu bản có text nội bộ vào app
                            boolean sent = mailService.sendMail(appEmail);
                            if (sent) {
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", email.getId()), senderAddr, senderPort);
                                logger.accept("[Thư mới từ " + sender + "]: Chủ đề: '" + email.getSubject() + "' gửi tới: " + email.getRecipientsDisplay());

                                // Push NEW_MAIL tới tất cả người nhận đang online
                                notifyOnlineRecipients(email, serverSocket);

                                // Chuyển tiếp ra Gmail thật qua SMTP (XOAUTH2 của sender)
                                if (smtpDeliveryService != null) {
                                    Map<String, String> userEmails = authService.getAllUserEmails();
                                    smtpDeliveryService.sendViaSmtp(email, userEmails);
                                }
                            } else {
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Lỗi khi lưu trữ thư"), senderAddr, senderPort);
                            }
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Dữ liệu email không hợp lệ"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case LIST_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        MailFolder folder = MailFolder.valueOf(params[0]);
                        int page = Integer.parseInt(params[1]);
                        int pageSize = Integer.parseInt(params[2]);

                        List<Email> mails = mailService.listMails(user, folder, page, pageSize);
                        int total = mailService.getMailCount(user, folder);
                        int unreadInbox = mailService.getUnreadInboxCount(user);

                        String jsonList = JsonUtil.toCompactJson(mails);
                        String base64List = JsonUtil.encodeBase64(jsonList);

                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", base64List, String.valueOf(total), String.valueOf(unreadInbox)), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case READ_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String mailId = params[0];
                        Email email = mailService.readMail(user, mailId);
                        if (email != null) {
                            String base64 = JsonUtil.encodeBase64(JsonUtil.toCompactJson(email));
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", base64), senderAddr, senderPort);
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Không tìm thấy thư"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case READ_THREAD: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String threadId = params[0];
                        List<Email> threadMessages = mailService.getThread(user, threadId);
                        String base64 = JsonUtil.encodeBase64(JsonUtil.toCompactJson(threadMessages));
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", base64, String.valueOf(threadMessages.size())), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case DELETE_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String mailId = params[0];
                        boolean permanent = Boolean.parseBoolean(params[1]);
                        boolean deleted = mailService.deleteMail(user, mailId, permanent);
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(deleted ? "OK" : "ERROR"), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case STAR_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String mailId = params[0];
                        boolean starred = Boolean.parseBoolean(params[1]);
                        boolean updated = mailService.toggleStar(user, mailId, starred);
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(updated ? "OK" : "ERROR"), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case MARK_READ: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String mailId = params[0];
                        boolean read = Boolean.parseBoolean(params[1]);
                        boolean updated = mailService.markRead(user, mailId, read);
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(updated ? "OK" : "ERROR"), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case SAVE_DRAFT: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String emailJson = JsonUtil.decodeBase64(params[0]);
                        Email draft = JsonUtil.fromJson(emailJson, Email.class);
                        if (draft != null) {
                            boolean saved = mailService.saveDraft(user, draft);
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(saved ? "OK" : "ERROR", draft.getId()), senderAddr, senderPort);
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Dữ liệu nháp không hợp lệ"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case SEARCH_MAIL: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String user = authService.getUsername(sessionKey);
                        String keyword = JsonUtil.decodeBase64(params[0]);
                        List<Email> results = mailService.searchMail(user, keyword);
                        String base64 = JsonUtil.encodeBase64(JsonUtil.toCompactJson(results));
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", base64, String.valueOf(results.size())), senderAddr, senderPort);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case GET_STATS: {
                    Map<String, Object> stats = mailService.getServerStats();
                    long totalEmails = ((Number) stats.getOrDefault("totalEmails", 0)).longValue();
                    long todayEmails = ((Number) stats.getOrDefault("todayEmails", 0)).longValue();
                    int onlineUsers = authService.getOnlineCount();
                    long attachSize = fileService.getTotalAttachmentsSize();
                    int attachCount = fileService.getAttachmentCount();

                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK",
                            String.valueOf(totalEmails),
                            String.valueOf(todayEmails),
                            String.valueOf(onlineUsers),
                            String.valueOf(attachSize),
                            String.valueOf(attachCount)
                    ), senderAddr, senderPort);
                    break;
                }
                case UPLOAD: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String filename = params[0];
                        int totalChunks = Integer.parseInt(params[1]);
                        String username = authService.getUsername(sessionKey);
                        logger.accept("Nhận tệp đính kèm '" + filename + "' (" + totalChunks + " phần) từ " + username);

                        try {
                            DatagramSocket fileSocket = new DatagramSocket();
                            int fileSocketPort = fileSocket.getLocalPort();
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", String.valueOf(fileSocketPort)), senderAddr, senderPort);

                            new Thread(() -> {
                                try {
                                    fileService.receiveFile(filename, totalChunks, fileSocket, senderAddr, senderPort);
                                    logger.accept("Đã nhận tệp đính kèm '" + filename + "' thành công từ " + username);
                                } catch (Exception e) {
                                    logger.accept("Lỗi nhận tệp đính kèm '" + filename + "': " + e.getMessage());
                                } finally {
                                    fileSocket.close();
                                }
                            }, "FileReceive-" + filename).start();
                        } catch (Exception e) {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Lỗi server: " + e.getMessage()), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                case DOWNLOAD: {
                    if (authService.isLoggedIn(sessionKey)) {
                        String dlFile = params[0];
                        String username = authService.getUsername(sessionKey);

                        int waitCount = 0;
                        while (!fileService.fileExists(dlFile) && waitCount < 20) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            waitCount++;
                        }

                        if (fileService.fileExists(dlFile)) {
                            int totalChunks = fileService.getTotalChunks(dlFile);
                            logger.accept("Gửi tệp đính kèm '" + dlFile + "' (" + totalChunks + " phần) cho " + username);

                            try {
                                DatagramSocket fileSocket = new DatagramSocket();
                                int fileSocketPort = fileSocket.getLocalPort();
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", String.valueOf(totalChunks), String.valueOf(fileSocketPort)), senderAddr, senderPort);

                                new Thread(() -> {
                                    try {
                                        fileService.sendFile(dlFile, fileSocket, senderAddr, senderPort);
                                        logger.accept("Đã gửi xong tệp '" + dlFile + "' cho " + username);
                                    } catch (Exception e) {
                                        logger.accept("Lỗi gửi tệp '" + dlFile + "': " + e.getMessage());
                                    } finally {
                                        fileSocket.close();
                                    }
                                }, "FileSend-" + dlFile).start();
                            } catch (Exception e) {
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Lỗi server: " + e.getMessage()), senderAddr, senderPort);
                            }
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Không tìm thấy tệp"), senderAddr, senderPort);
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void notifyOnlineRecipients(Email email, DatagramSocket serverSocket) {
        Set<String> all = new HashSet<>();
        if (email.getRecipients() != null) all.addAll(email.getRecipients());
        if (email.getCc() != null) all.addAll(email.getCc());
        if (email.getBcc() != null) all.addAll(email.getBcc());

        String pushMsg = ProtocolHelper.buildPush(Command.NEW_MAIL, email.getSender(), email.getSubject(), email.getId());

        for (ClientSession session : authService.getOnlineSessions()) {
            String sessUser = session.getUsername();
            String sessEmail = authService.getUserEmail(sessUser);
            if (all.contains(sessUser) || (sessEmail != null && all.contains(sessEmail))) {
                try {
                    UDPUtil.sendString(serverSocket, pushMsg, session.getAddress(), session.getListenerPort());
                } catch (Exception e) {
                    logger.accept("Lỗi gửi thông báo thư mới tới " + sessUser + ": " + e.getMessage());
                }
            }
        }
    }
}
