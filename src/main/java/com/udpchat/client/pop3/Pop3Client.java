package com.udpchat.client.pop3;

import com.udpchat.common.model.EmailMessage;
import com.udpchat.common.util.MimeUtil;
import com.udpchat.common.util.NetworkUtil;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client nhận thư điện tử thông qua giao thức POP3 chuẩn RFC 1939 trên nền tảng TCP Socket
 */
public class Pop3Client {

    public static class Pop3Result {
        public final boolean success;
        public final String message;
        public final List<EmailMessage> emails;

        public Pop3Result(boolean success, String message, List<EmailMessage> emails) {
            this.success = success;
            this.message = message;
            this.emails = emails != null ? emails : new ArrayList<>();
        }
    }

    /**
     * Tải toàn bộ danh sách thư từ máy chủ POP3
     */
    public static Pop3Result fetchEmails(String host, int port, String username, String password) {
        List<EmailMessage> resultList = new ArrayList<>();

        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(10000);

            // 1. Nhận lời chào +OK
            String banner = NetworkUtil.readLine(reader);
            if (banner == null || !banner.startsWith("+OK")) {
                return new Pop3Result(false, "Máy chủ POP3 không sẵn sàng: " + banner, resultList);
            }

            // 2. Gửi USER
            NetworkUtil.writeLine(writer, "USER " + username);
            String resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("+OK")) {
                return new Pop3Result(false, "Tên người dùng không hợp lệ: " + resp, resultList);
            }

            // 3. Gửi PASS
            NetworkUtil.writeLine(writer, "PASS " + password);
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("+OK")) {
                return new Pop3Result(false, "Sai mật khẩu hoặc tài khoản chưa đăng ký: " + resp, resultList);
            }

            // 4. Gửi STAT để lấy số lượng thư
            NetworkUtil.writeLine(writer, "STAT");
            resp = NetworkUtil.readLine(reader);
            if (resp == null || !resp.startsWith("+OK")) {
                return new Pop3Result(false, "Lỗi kiểm tra trạng thái hộp thư: " + resp, resultList);
            }

            String[] statParts = resp.split("\\s+");
            int totalMessages = 0;
            if (statParts.length >= 2) {
                try {
                    totalMessages = Integer.parseInt(statParts[1]);
                } catch (NumberFormatException ignored) {}
            }

            // 5. Tải từng thư bằng lệnh RETR
            for (int i = 1; i <= totalMessages; i++) {
                NetworkUtil.writeLine(writer, "RETR " + i);
                String retrResp = NetworkUtil.readLine(reader);
                if (retrResp != null && retrResp.startsWith("+OK")) {
                    StringBuilder mimeBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals(".")) {
                            break;
                        }
                        if (line.startsWith("..")) {
                            line = line.substring(1);
                        }
                        mimeBuilder.append(line).append("\r\n");
                    }

                    EmailMessage email = MimeUtil.parseMimeString(mimeBuilder.toString());
                    resultList.add(email);
                }
            }

            // 6. Gửi QUIT
            NetworkUtil.writeLine(writer, "QUIT");
            NetworkUtil.readLine(reader);

            return new Pop3Result(true, "Đã tải " + resultList.size() + " thư qua POP3", resultList);
        } catch (Exception e) {
            return new Pop3Result(false, "Lỗi kết nối POP3 tới " + host + ":" + port + " (" + e.getMessage() + ")", resultList);
        }
    }

    /**
     * Xóa một thư trên máy chủ POP3 bằng lệnh DELE
     */
    public static boolean deleteEmail(String host, int port, String username, String password, int msgIndex) {
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(10000);

            // Banner, USER, PASS
            if (!NetworkUtil.readLine(reader).startsWith("+OK")) return false;
            NetworkUtil.writeLine(writer, "USER " + username);
            if (!NetworkUtil.readLine(reader).startsWith("+OK")) return false;
            NetworkUtil.writeLine(writer, "PASS " + password);
            if (!NetworkUtil.readLine(reader).startsWith("+OK")) return false;

            // Gửi DELE
            NetworkUtil.writeLine(writer, "DELE " + msgIndex);
            String resp = NetworkUtil.readLine(reader);

            // Gửi QUIT
            NetworkUtil.writeLine(writer, "QUIT");
            NetworkUtil.readLine(reader);

            return resp != null && resp.startsWith("+OK");
        } catch (Exception e) {
            return false;
        }
    }
}
