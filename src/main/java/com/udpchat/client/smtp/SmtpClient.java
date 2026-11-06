package com.udpchat.client.smtp;

import com.udpchat.common.model.EmailMessage;
import com.udpchat.common.util.MimeUtil;
import com.udpchat.common.util.NetworkUtil;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client gửi thư điện tử thông qua giao thức SMTP chuẩn RFC 5321 trên nền tảng TCP Socket
 */
public class SmtpClient {

    public static class SmtpResult {
        public final boolean success;
        public final String message;

        public SmtpResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static SmtpResult sendEmail(String host, int port, EmailMessage email) {
        if (email == null) {
            return new SmtpResult(false, "Đối tượng email không hợp lệ (null)");
        }

        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(10000);

            // 1. Chờ lời chào 220 từ Server
            String banner = NetworkUtil.readLine(reader);
            if (banner == null || !banner.startsWith("220")) {
                return new SmtpResult(false, "Máy chủ không sẵn sàng: " + banner);
            }

            // 2. Gửi HELO
            NetworkUtil.writeLine(writer, "HELO localhost");
            String resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("250")) {
                return new SmtpResult(false, "Lỗi phản hồi HELO: " + resp);
            }

            // 3. Gửi MAIL FROM
            String from = email.getSender() != null ? email.getSender() : "user@localhost";
            NetworkUtil.writeLine(writer, "MAIL FROM: <" + from + ">");
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("250")) {
                return new SmtpResult(false, "Lỗi người gửi (MAIL FROM): " + resp);
            }

            // 4. Gửi RCPT TO
            String to = email.getRecipient() != null ? email.getRecipient() : "user@localhost";
            NetworkUtil.writeLine(writer, "RCPT TO: <" + to + ">");
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("250")) {
                return new SmtpResult(false, "Lỗi người nhận (RCPT TO): " + resp);
            }

            // 5. Gửi DATA
            NetworkUtil.writeLine(writer, "DATA");
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("354")) {
                return new SmtpResult(false, "Máy chủ từ chối nhận dữ liệu (DATA): " + resp);
            }

            // 6. Gửi dữ liệu MIME kèm Dot-Stuffing
            String mimeData = MimeUtil.toMimeString(email);
            String[] lines = mimeData.split("\r?\n");
            for (String l : lines) {
                if (l.startsWith(".")) {
                    l = "." + l; // Dot-stuffing RFC 5321
                }
                NetworkUtil.writeLine(writer, l);
            }
            // Kết thúc khối DATA với dấu .
            NetworkUtil.writeLine(writer, ".");
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("250")) {
                return new SmtpResult(false, "Máy chủ báo lỗi khi xử lý thư: " + resp);
            }

            // 7. Gửi QUIT
            NetworkUtil.writeLine(writer, "QUIT");
            NetworkUtil.readLine(reader);

            return new SmtpResult(true, "Gửi email thành công qua SMTP tới " + to);
        } catch (Exception e) {
            return new SmtpResult(false, "Lỗi kết nối SMTP tới " + host + ":" + port + " (" + e.getMessage() + ")");
        }
    }
}
