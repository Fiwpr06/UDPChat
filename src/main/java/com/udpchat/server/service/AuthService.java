package com.udpchat.server.service;

import com.udpchat.server.model.ClientSession;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AuthService {
    private final ConcurrentHashMap<String, ClientSession> onlineSessions = new ConcurrentHashMap<>();
    private final Path usersDir;

    public AuthService() {
        usersDir = Paths.get("server-data", "users");
        try {
            Files.createDirectories(usersDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Đăng ký người dùng mới
    public String register(String username, String password) {
        Path userFile = usersDir.resolve(username + ".txt");
        if (Files.exists(userFile)) {
            return "Lỗi: Tài khoản '" + username + "' đã tồn tại";
        }
        try {
            Files.writeString(userFile, "password=" + password, java.nio.charset.StandardCharsets.UTF_8);
            return "Thành công: Đăng ký tài khoản '" + username + "' thành công";
        } catch (IOException e) {
            return "Lỗi: Không thể lưu thông tin tài khoản";
        }
    }

    // Đăng nhập
    public String login(String username, String password, InetAddress addr, int reqPort, int listenerPort) {
        Path userFile = usersDir.resolve(username + ".txt");
        if (!Files.exists(userFile)) {
            return "Lỗi: Tài khoản '" + username + "' không tồn tại";
        }
        try {
            String content = Files.readString(userFile, java.nio.charset.StandardCharsets.UTF_8);
            if (content.trim().equals("password=" + password)) {
                String sessionKey = makeSessionKey(addr, reqPort);
                ClientSession session = new ClientSession(username, addr, reqPort, listenerPort);
                onlineSessions.put(sessionKey, session);
                return "Thành công: Đăng nhập thành công";
            } else {
                return "Lỗi: Mật khẩu không chính xác";
            }
        } catch (IOException e) {
            return "Lỗi: Quá trình xác thực đăng nhập thất bại";
        }
    }

    // Đăng xuất
    public String logout(String sessionKey) {
        onlineSessions.remove(sessionKey);
        return "Thành công: Đã đăng xuất";
    }

    public boolean isLoggedIn(String sessionKey) {
        return onlineSessions.containsKey(sessionKey);
    }

    public String getUsername(String sessionKey) {
        ClientSession session = onlineSessions.get(sessionKey);
        return session != null ? session.getUsername() : null;
    }

    public ClientSession getSession(String sessionKey) {
        return onlineSessions.get(sessionKey);
    }

    public Collection<ClientSession> getOnlineSessions() {
        return onlineSessions.values();
    }

    public Collection<ClientSession> getOnlineSessionsExcept(String excludeKey) {
        return onlineSessions.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(excludeKey))
                .map(java.util.Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    public int getOnlineCount() {
        return onlineSessions.size();
    }

    public static String makeSessionKey(InetAddress addr, int port) {
        return addr.getHostAddress() + ":" + port;
    }
}
