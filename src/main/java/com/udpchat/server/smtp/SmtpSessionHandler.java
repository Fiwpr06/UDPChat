package com.udpchat.server.smtp;

import com.udpchat.common.model.EmailMessage;
import com.udpchat.common.util.MimeUtil;
import com.udpchat.common.util.NetworkUtil;
import com.udpchat.database.EmailDao;
import com.udpchat.server.service.GmailForwarder;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Xử lý phiên giao dịch SMTP chuẩn RFC 5321 cho từng Client kết nối TCP
 */
public class SmtpSessionHandler implements Runnable {
    private final Socket socket;
    private final EmailDao emailDao;
    private final GmailForwarder gmailForwarder;
    private final Consumer<String> logger;

    private String sender = "";
    private String recipient = "";

    public SmtpSessionHandler(Socket socket, EmailDao emailDao, GmailForwarder gmailForwarder, Consumer<String> logger) {
        this.socket = socket;
        this.emailDao = emailDao;
        this.gmailForwarder = gmailForwarder;
        this.logger = logger != null ? logger : System.out::println;
    }

    @Override
    public void run() {
        String clientIp = socket.getInetAddress().getHostAddress();
        logger.accept("[SMTP Server] Kết nối mới từ: " + clientIp + ":" + socket.getPort());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // 1. Gửi lời chào sẵn sàng
            NetworkUtil.writeLine(writer, "220 Simple SMTP Server Ready (com.udpchat)");

            String line;
            while ((line = NetworkUtil.readLine(reader)) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String upper = line.toUpperCase();

                if (upper.startsWith("HELO") || upper.startsWith("EHLO")) {
                    NetworkUtil.writeLine(writer, "250 Hello " + clientIp);
                } else if (upper.startsWith("MAIL FROM:")) {
                    sender = extractEmailAddress(line.substring(10).trim());
                    NetworkUtil.writeLine(writer, "250 Sender <" + sender + "> OK");
                } else if (upper.startsWith("RCPT TO:")) {
                    recipient = extractEmailAddress(line.substring(8).trim());
                    NetworkUtil.writeLine(writer, "250 Recipient <" + recipient + "> OK");
                } else if (upper.equals("DATA")) {
                    NetworkUtil.writeLine(writer, "354 Start mail input; end with <CRLF>.<CRLF>");

                    StringBuilder dataBuilder = new StringBuilder();
                    String dataLine;
                    while ((dataLine = reader.readLine()) != null) {
                        if (dataLine.equals(".")) {
                            break;
                        }
                        // Xử lý dot-stuffing RFC 5321 (.. -> .)
                        if (dataLine.startsWith("..")) {
                            dataLine = dataLine.substring(1);
                        }
                        dataBuilder.append(dataLine).append("\r\n");
                    }

                    // Phân tích dữ liệu MIME thành EmailMessage
                    EmailMessage email = MimeUtil.parseMimeString(dataBuilder.toString());
                    if (email.getSender() == null || email.getSender().isEmpty() || email.getSender().contains("unknown")) {
                        email.setSender(sender);
                    }
                    if (email.getRecipient() == null || email.getRecipient().isEmpty() || email.getRecipient().contains("unknown")) {
                        email.setRecipient(recipient);
                    }

                    // Lưu vào CSDL Server
                    int savedId = emailDao.saveEmail(email);
                    if (savedId > 0) {
                        logger.accept("[SMTP Server] ✓ Đã nhận và lưu thư ID #" + savedId +
                                " | Từ: " + email.getSender() + " -> Tới: " + email.getRecipient() +
                                " | Tiêu đề: " + email.getSubject() +
                                (email.hasAttachments() ? " [Đính kèm " + email.getAttachments().size() + " tệp]" : ""));

                        // Chuyển tiếp ra Gmail nếu người nhận là địa chỉ Gmail thật
                        if (recipient.toLowerCase().endsWith("@gmail.com") && gmailForwarder != null) {
                            gmailForwarder.forwardAsync(email);
                        }

                        NetworkUtil.writeLine(writer, "250 OK: Message accepted for delivery (ID: " + savedId + ")");
                    } else {
                        NetworkUtil.writeLine(writer, "451 Requested action aborted: Local error in processing");
                    }
                } else if (upper.equals("RSET")) {
                    sender = "";
                    recipient = "";
                    NetworkUtil.writeLine(writer, "250 OK");
                } else if (upper.equals("NOOP")) {
                    NetworkUtil.writeLine(writer, "250 OK");
                } else if (upper.equals("QUIT")) {
                    NetworkUtil.writeLine(writer, "221 2.0.0 Service closing transmission channel");
                    break;
                } else {
                    NetworkUtil.writeLine(writer, "500 Syntax error, command unrecognized: " + line);
                }
            }
        } catch (Exception e) {
            logger.accept("[SMTP Server] Ngắt kết nối từ " + clientIp + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private String extractEmailAddress(String raw) {
        if (raw.startsWith("<") && raw.endsWith(">")) {
            return raw.substring(1, raw.length() - 1).trim();
        }
        return raw.trim();
    }
}
