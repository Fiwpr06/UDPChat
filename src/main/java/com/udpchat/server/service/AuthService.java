package com.udpchat.server.service;

import com.udpchat.server.database.DatabaseManager;
import com.udpchat.server.model.ClientSession;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Quản lý xác thực người dùng và phiên làm việc, lưu trữ vĩnh viễn trên MySQL
 */
public class AuthService {
    private final ConcurrentHashMap<String, ClientSession> onlineSessions = new ConcurrentHashMap<>();

    public AuthService() {
        // Đảm bảo Database schema đã sẵn sàng
        try {
            DatabaseManager.initialize();
        } catch (Exception e) {
            System.err.println("[AuthService] Lỗi kết nối CSDL: " + e.getMessage());
        }
    }

    // Đăng ký người dùng mới
    public String register(String username, String password) {
        return register(username, password, "127.0.0.1", null);
    }

    public String register(String username, String password, InetAddress addr) {
        return register(username, password, addr != null ? normalizeAddress(addr) : "127.0.0.1", null);
    }

    public String register(String username, String password, String clientIp) {
        return register(username, password, clientIp, null);
    }

    public String register(String username, String password, String clientIp, String email) {
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return "Lỗi: Tên đăng nhập và mật khẩu không được để trống";
        }
        String cleanUser = username.trim();
        String cleanIp = clientIp != null ? clientIp.trim() : "127.0.0.1";
        String cleanEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : null;

        String checkSql = "SELECT id FROM users WHERE username = ?";
        String insertSql = "INSERT INTO users (username, password_hash, gmail_address, client_ip) VALUES (?, ?, ?, ?)";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, cleanUser);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    return "Lỗi: Tài khoản '" + cleanUser + "' đã tồn tại";
                }
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, cleanUser);
                insertStmt.setString(2, password);
                insertStmt.setString(3, cleanEmail);
                insertStmt.setString(4, cleanIp);
                insertStmt.executeUpdate();
                return "Thành công: Đăng ký tài khoản '" + cleanUser + "' thành công";
            }
        } catch (SQLException e) {
            return "Lỗi CSDL khi đăng ký: " + e.getMessage();
        }
    }

    // Đăng nhập
    public String login(String username, String password, InetAddress addr, int reqPort, int listenerPort) {
        if (username == null || password == null) {
            return "Lỗi: Thông tin đăng nhập không hợp lệ";
        }
        String cleanUser = username.trim();
        String selectSql = "SELECT password_hash FROM users WHERE username = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, cleanUser);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return "Lỗi: Tài khoản '" + cleanUser + "' không tồn tại";
                }
                String savedPassword = rs.getString("password_hash");
                if (savedPassword != null && savedPassword.equals(password)) {
                    String sessionKey = makeSessionKey(addr, reqPort);
                    InetAddress normalizedAddr;
                    try {
                        normalizedAddr = InetAddress.getByName(normalizeAddress(addr));
                    } catch (Exception e) {
                        normalizedAddr = addr;
                    }
                    ClientSession session = new ClientSession(cleanUser, normalizedAddr, reqPort, listenerPort);
                    onlineSessions.put(sessionKey, session);
                    return "Thành công: Đăng nhập thành công";
                } else {
                    return "Lỗi: Mật khẩu không chính xác";
                }
            }
        } catch (SQLException e) {
            return "Lỗi CSDL khi đăng nhập: " + e.getMessage();
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
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    public int getOnlineCount() {
        return onlineSessions.size();
    }

    /**
     * Lấy địa chỉ email thực tế của người dùng từ CSDL MySQL
     */
    public String getUserEmail(String username) {
        if (username == null || username.trim().isEmpty()) return null;
        if (username.contains("@")) {
            return username.trim();
        }
        String sql = "SELECT gmail_address FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String email = rs.getString("gmail_address");
                    return (email != null && !email.trim().isEmpty()) ? email.trim() : null;
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /**
     * Cập nhật địa chỉ email thực tế cho người dùng trong CSDL MySQL
     */
    public boolean setUserEmail(String username, String email) {
        if (username == null || username.trim().isEmpty()) return false;
        String cleanEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : null;
        String sql = "UPDATE users SET gmail_address = ? WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, cleanEmail);
            stmt.setString(2, username.trim());
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Tra cứu ngược: tìm username nội bộ tương ứng với địa chỉ Gmail
     */
    public String getUsernameByEmail(String email) {
        if (email == null || email.trim().isEmpty()) return null;
        String sql = "SELECT username FROM users WHERE LOWER(gmail_address) = LOWER(?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("username");
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /**
     * Lấy bảng ánh xạ toàn bộ username -> email thực tế từ MySQL
     */
    public Map<String, String> getAllUserEmails() {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT username, gmail_address FROM users WHERE gmail_address IS NOT NULL AND gmail_address != ''";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("username"), rs.getString("gmail_address"));
            }
        } catch (SQLException ignored) {}
        return map;
    }

    public static String makeSessionKey(InetAddress addr, int port) {
        return normalizeAddress(addr) + ":" + port;
    }

    /**
     * Chuẩn hóa địa chỉ IP: chuyển IPv6 loopback (::1) về IPv4 loopback (127.0.0.1)
     */
    public static String normalizeAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return "127.0.0.1";
        }
        if (addr instanceof java.net.Inet6Address) {
            java.net.Inet6Address ipv6 = (java.net.Inet6Address) addr;
            byte[] bytes = ipv6.getAddress();
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
