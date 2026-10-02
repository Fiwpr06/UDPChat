package com.udpchat.server.network;

import com.udpchat.server.model.ClientSession;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MessageService;
import com.udpchat.shared.protocol.Command;
import com.udpchat.shared.protocol.ProtocolHelper;
import com.udpchat.shared.protocol.UDPConstants;
import com.udpchat.shared.util.UDPUtil;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class RequestHandler {
    private final AuthService authService;
    private final MessageService messageService;
    private final FileService fileService;
    private final Consumer<String> logger;

    public RequestHandler(AuthService authService, MessageService messageService, FileService fileService, Consumer<String> logger) {
        this.authService = authService;
        this.messageService = messageService;
        this.fileService = fileService;
        this.logger = logger;
    }

    public void handle(String rawMessage, InetAddress senderAddr, int senderPort, DatagramSocket serverSocket) {
        try {
            Command cmd = ProtocolHelper.parseCommand(rawMessage);
            String[] params = ProtocolHelper.parseParams(rawMessage);
            String sessionKey = AuthService.makeSessionKey(senderAddr, senderPort);
            
            logger.accept("Received " + cmd + " from " + sessionKey);

            if (cmd == null) return;

            switch (cmd) {
                case REGISTER:
                    String regStatus = authService.register(params[0], params[1]);
                    boolean regOk = regStatus.contains("Thành công") || regStatus.startsWith("Success");
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(regOk ? "OK" : "ERROR", regStatus), senderAddr, senderPort);
                    logger.accept("Đăng ký tài khoản: " + params[0] + " từ " + sessionKey + " -> " + (regOk ? "Thành công" : regStatus));
                    break;
                case LOGIN:
                    int listenerPort = Integer.parseInt(params[2]);
                    String loginStatus = authService.login(params[0], params[1], senderAddr, senderPort, listenerPort);
                    boolean loginOk = loginStatus.contains("Thành công") || loginStatus.startsWith("Success");
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse(loginOk ? "OK" : "ERROR", loginStatus), senderAddr, senderPort);
                    if (loginOk) {
                        logger.accept("Người dùng '" + params[0] + "' đăng nhập thành công (Cổng nhận: " + listenerPort + ")");
                    } else {
                        logger.accept("Đăng nhập thất bại cho '" + params[0] + "': " + loginStatus);
                    }
                    break;
                case MESSAGE:
                    if (authService.isLoggedIn(sessionKey)) {
                        String username = authService.getUsername(sessionKey);
                        String content = "";
                        int delimIdx = rawMessage.indexOf(UDPConstants.DELIMITER);
                        if (delimIdx != -1 && delimIdx < rawMessage.length() - 1) {
                            content = rawMessage.substring(delimIdx + 1);
                        }
                        messageService.saveMessage(senderAddr.getHostAddress(), username, content);
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        String pushMsg = ProtocolHelper.buildPush(Command.INCOMING_MSG, username, timestamp, content);
                        
                        int forwardedCount = 0;
                        for (ClientSession session : authService.getOnlineSessionsExcept(sessionKey)) {
                            UDPUtil.sendString(serverSocket, pushMsg, session.getAddress(), session.getListenerPort());
                            forwardedCount++;
                        }
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK"), senderAddr, senderPort);
                        logger.accept("[" + username + " -> Chuyển tiếp tới " + forwardedCount + " client]: " + content);
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                        logger.accept("Từ chối tin nhắn từ " + sessionKey + ": Chưa đăng nhập");
                    }
                    break;
                case UPLOAD:
                    if (authService.isLoggedIn(sessionKey)) {
                        String filename = params[0];
                        int totalChunks = Integer.parseInt(params[1]);
                        String username = authService.getUsername(sessionKey);
                        logger.accept("Bắt đầu nhận file '" + filename + "' (" + totalChunks + " phần) từ " + username);
                        // Gửi OK qua server socket, nhưng nhận file qua socket riêng
                        // để không block luồng chính của server
                        try {
                            DatagramSocket fileSocket = new DatagramSocket();
                            int fileSocketPort = fileSocket.getLocalPort();
                            // Thông báo cho client biết port riêng để gửi chunks
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", String.valueOf(fileSocketPort)), senderAddr, senderPort);
                            // Xử lý nhận file trên thread riêng
                            new Thread(() -> {
                                try {
                                    fileService.receiveFile(filename, totalChunks, fileSocket, senderAddr, senderPort);
                                    logger.accept("Đã nhận file '" + filename + "' thành công từ " + username);
                                } catch (Exception e) {
                                    logger.accept("Lỗi nhận file '" + filename + "' từ " + username + ": " + e.getMessage());
                                } finally {
                                    fileSocket.close();
                                }
                            }, "FileReceive-" + filename).start();
                        } catch (Exception e) {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Lỗi server: " + e.getMessage()), senderAddr, senderPort);
                            logger.accept("Lỗi tạo socket nhận file: " + e.getMessage());
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                case DOWNLOAD:
                    if (authService.isLoggedIn(sessionKey)) {
                        String dlFile = params[0];
                        String username = authService.getUsername(sessionKey);
                        if (fileService.fileExists(dlFile)) {
                            int totalChunks = fileService.getTotalChunks(dlFile);
                            logger.accept("Gửi file download '" + dlFile + "' (" + totalChunks + " phần) cho " + username);
                            // Tạo socket riêng cho download để không block luồng chính
                            try {
                                DatagramSocket fileSocket = new DatagramSocket();
                                int fileSocketPort = fileSocket.getLocalPort();
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK", String.valueOf(totalChunks), String.valueOf(fileSocketPort)), senderAddr, senderPort);
                                // Xử lý gửi file trên thread riêng
                                new Thread(() -> {
                                    try {
                                        fileService.sendFile(dlFile, fileSocket, senderAddr, senderPort);
                                        logger.accept("Đã gửi xong file '" + dlFile + "' cho " + username);
                                    } catch (Exception e) {
                                        logger.accept("Lỗi gửi file '" + dlFile + "' cho " + username + ": " + e.getMessage());
                                    } finally {
                                        fileSocket.close();
                                    }
                                }, "FileSend-" + dlFile).start();
                            } catch (Exception e) {
                                UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Lỗi server: " + e.getMessage()), senderAddr, senderPort);
                                logger.accept("Lỗi tạo socket gửi file: " + e.getMessage());
                            }
                        } else {
                            UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Không tìm thấy file"), senderAddr, senderPort);
                            logger.accept("Tải file thất bại: Không tìm thấy file '" + dlFile + "' trên server");
                        }
                    } else {
                        UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("ERROR", "Chưa đăng nhập"), senderAddr, senderPort);
                    }
                    break;
                case LOGOUT:
                    String logoutUser = authService.getUsername(sessionKey);
                    authService.logout(sessionKey);
                    UDPUtil.sendString(serverSocket, ProtocolHelper.buildResponse("OK"), senderAddr, senderPort);
                    logger.accept("Người dùng '" + (logoutUser != null ? logoutUser : sessionKey) + "' đã đăng xuất.");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

