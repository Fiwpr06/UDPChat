package com.udpchat.server.pop3;

import com.udpchat.common.model.EmailMessage;
import com.udpchat.common.model.User;
import com.udpchat.common.util.MimeUtil;
import com.udpchat.common.util.NetworkUtil;
import com.udpchat.database.EmailDao;
import com.udpchat.database.UserDao;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Xử lý phiên giao dịch POP3 chuẩn RFC 1939 cho từng Client kết nối TCP
 */
public class Pop3SessionHandler implements Runnable {
    private enum State { AUTHORIZATION, TRANSACTION, UPDATE }

    private final Socket socket;
    private final UserDao userDao;
    private final EmailDao emailDao;
    private final Consumer<String> logger;

    private State state = State.AUTHORIZATION;
    private String pendingUser = null;
    private User authenticatedUser = null;
    private List<EmailMessage> messages = new ArrayList<>();

    public Pop3SessionHandler(Socket socket, UserDao userDao, EmailDao emailDao, Consumer<String> logger) {
        this.socket = socket;
        this.userDao = userDao;
        this.emailDao = emailDao;
        this.logger = logger != null ? logger : System.out::println;
    }

    @Override
    public void run() {
        String clientIp = socket.getInetAddress().getHostAddress();
        logger.accept("[POP3 Server] Kết nối mới từ: " + clientIp + ":" + socket.getPort());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // 1. Gửi lời chào sẵn sàng
            NetworkUtil.writeLine(writer, "+OK POP3 Server Ready (com.udpchat)");

            String line;
            while ((line = NetworkUtil.readLine(reader)) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String upper = line.toUpperCase();

                if (state == State.AUTHORIZATION) {
                    if (upper.startsWith("USER ")) {
                        pendingUser = line.substring(5).trim();
                        NetworkUtil.writeLine(writer, "+OK User name accepted");
                    } else if (upper.startsWith("PASS ")) {
                        String password = line.substring(5).trim();
                        if (pendingUser != null) {
                            User u = userDao.authenticate(pendingUser, password);
                            if (u != null) {
                                authenticatedUser = u;
                                state = State.TRANSACTION;
                                // Nạp danh sách thư
                                messages = emailDao.getInbox(authenticatedUser.getUsername());
                                logger.accept("[POP3 Server] Người dùng '" + authenticatedUser.getUsername() + "' đăng nhập thành công. Có " + messages.size() + " thư.");
                                NetworkUtil.writeLine(writer, "+OK Logged in, mailbox has " + messages.size() + " message(s)");
                            } else {
                                logger.accept("[POP3 Server] Đăng nhập thất bại cho user: " + pendingUser);
                                NetworkUtil.writeLine(writer, "-ERR Invalid username or password");
                            }
                        } else {
                            NetworkUtil.writeLine(writer, "-ERR Send USER first");
                        }
                    } else if (upper.equals("QUIT")) {
                        NetworkUtil.writeLine(writer, "+OK Bye");
                        break;
                    } else {
                        NetworkUtil.writeLine(writer, "-ERR Unknown command in AUTHORIZATION state");
                    }
                } else if (state == State.TRANSACTION) {
                    if (upper.equals("STAT")) {
                        long totalSize = messages.size() * 1024L;
                        NetworkUtil.writeLine(writer, "+OK " + messages.size() + " " + totalSize);
                    } else if (upper.equals("LIST")) {
                        NetworkUtil.writeLine(writer, "+OK " + messages.size() + " messages");
                        for (int i = 0; i < messages.size(); i++) {
                            NetworkUtil.writeLine(writer, (i + 1) + " 1024");
                        }
                        NetworkUtil.writeLine(writer, ".");
                    } else if (upper.startsWith("LIST ")) {
                        try {
                            int idx = Integer.parseInt(line.substring(5).trim()) - 1;
                            if (idx >= 0 && idx < messages.size()) {
                                NetworkUtil.writeLine(writer, "+OK " + (idx + 1) + " 1024");
                            } else {
                                NetworkUtil.writeLine(writer, "-ERR No such message");
                            }
                        } catch (Exception e) {
                            NetworkUtil.writeLine(writer, "-ERR Invalid message number");
                        }
                    } else if (upper.startsWith("RETR ")) {
                        try {
                            int idx = Integer.parseInt(line.substring(5).trim()) - 1;
                            if (idx >= 0 && idx < messages.size()) {
                                EmailMessage em = messages.get(idx);
                                // Lấy đầy đủ bản ghi từ DB kèm attachments
                                EmailMessage fullEm = emailDao.getEmailById(em.getId());
                                if (fullEm == null) fullEm = em;

                                String mimeText = MimeUtil.toMimeString(fullEm);
                                NetworkUtil.writeLine(writer, "+OK " + mimeText.length() + " octets");

                                // Gửi nội dung MIME và kết thúc với CRLF.CRLF
                                String[] mimeLines = mimeText.split("\r?\n");
                                for (String mLine : mimeLines) {
                                    if (mLine.startsWith(".")) {
                                        mLine = "." + mLine; // Dot-stuffing
                                    }
                                    NetworkUtil.writeLine(writer, mLine);
                                }
                                NetworkUtil.writeLine(writer, ".");

                                // Đánh dấu đã đọc
                                emailDao.markAsRead(fullEm.getId());
                                logger.accept("[POP3 Server] Người dùng '" + authenticatedUser.getUsername() + "' đã đọc thư ID #" + fullEm.getId());
                            } else {
                                NetworkUtil.writeLine(writer, "-ERR No such message");
                            }
                        } catch (Exception e) {
                            NetworkUtil.writeLine(writer, "-ERR Invalid message number");
                        }
                    } else if (upper.startsWith("DELE ")) {
                        try {
                            int idx = Integer.parseInt(line.substring(5).trim()) - 1;
                            if (idx >= 0 && idx < messages.size()) {
                                EmailMessage em = messages.remove(idx);
                                emailDao.deleteEmail(em.getId());
                                logger.accept("[POP3 Server] Người dùng '" + authenticatedUser.getUsername() + "' đã xóa thư ID #" + em.getId());
                                NetworkUtil.writeLine(writer, "+OK Message marked for deletion");
                            } else {
                                NetworkUtil.writeLine(writer, "-ERR No such message");
                            }
                        } catch (Exception e) {
                            NetworkUtil.writeLine(writer, "-ERR Invalid message number");
                        }
                    } else if (upper.equals("NOOP") || upper.equals("RSET")) {
                        NetworkUtil.writeLine(writer, "+OK");
                    } else if (upper.equals("QUIT")) {
                        state = State.UPDATE;
                        NetworkUtil.writeLine(writer, "+OK Logging out, bye");
                        break;
                    } else {
                        NetworkUtil.writeLine(writer, "-ERR Unknown command: " + line);
                    }
                }
            }
        } catch (Exception e) {
            logger.accept("[POP3 Server] Ngắt kết nối từ " + clientIp + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
