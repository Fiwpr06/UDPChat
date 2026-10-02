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
        return register(username, password, "127.0.0.1");
    }

    public String register(String username, String password, InetAddress addr) {
        return register(username, password, addr != null ? normalizeAddress(addr) : "127.0.0.1");
    }

    public String register(String username, String password, String clientIp) {
        Path userFile = usersDir.resolve(username + ".txt");
        if (Files.exists(userFile)) {
            return "Lỗi: Tài khoản '" + username + "' đã tồn tại";
        }
        try {
            String content = "password=" + password + System.lineSeparator() + "ip=" + (clientIp != null ? clientIp : "127.0.0.1");
            Files.writeString(userFile, content, java.nio.charset.StandardCharsets.UTF_8);
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
            String savedPassword = null;
            for (String line : content.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("password=")) {
                    savedPassword = line.substring("password=".length());
                    break;
                }
            }
            if (savedPassword != null && savedPassword.equals(password)) {
                String sessionKey = makeSessionKey(addr, reqPort);
                // Chuẩn hóa địa chỉ IP để đảm bảo push message đến đúng đích
                InetAddress normalizedAddr;
                try {
                    normalizedAddr = InetAddress.getByName(normalizeAddress(addr));
                } catch (Exception e) {
                    normalizedAddr = addr;
                }
                ClientSession session = new ClientSession(username, normalizedAddr, reqPort, listenerPort);
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
        return normalizeAddress(addr) + ":" + port;
    }

    /**
     * Chuẩn hóa địa chỉ IP: chuyển IPv6 loopback (::1) về IPv4 loopback (127.0.0.1)
     * để tránh sessionKey không nhất quán khi client chạy trên cùng máy host.
     */
    public static String normalizeAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return "127.0.0.1";
        }
        // Xử lý IPv4-mapped IPv6 addresses (e.g., ::ffff:192.168.1.x)
        if (addr instanceof java.net.Inet6Address) {
            java.net.Inet6Address ipv6 = (java.net.Inet6Address) addr;
            byte[] bytes = ipv6.getAddress();
            // Check if it's an IPv4-mapped IPv6 address (::ffff:x.x.x.x)
            boolean isIPv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isIPv4Mapped = false; break; }
            }
            if (isIPv4Mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                return String.format("%d.%d.%d.%d",
                        bytes[12] & 0xFF, bytes[13] & 0xFF,
                        bytes[14] & 0xFF, bytes[15] & 0xFF);
            }
        }
        return addr.getHostAddress();
    }
}
