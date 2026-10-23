package com.udpchat.server.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.udpchat.server.database.DatabaseManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Xử lý luồng trao đổi token, làm mới token và tương tác OAuth 2.0 với Google
 */
public class OAuthService {
    private final OAuthConfig config;
    private final AuthService authService;
    private final HttpClient httpClient;

    public static class TokenResult {
        public final boolean success;
        public final String email;
        public final String accessToken;
        public final String refreshToken;
        public final int expiresIn;
        public final String errorMessage;

        public TokenResult(boolean success, String email, String accessToken, String refreshToken, int expiresIn, String errorMessage) {
            this.success = success;
            this.email = email;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
            this.errorMessage = errorMessage;
        }

        public static TokenResult fail(String msg) {
            return new TokenResult(false, null, null, null, 0, msg);
        }

        public static TokenResult ok(String email, String accessToken, String refreshToken, int expiresIn) {
            return new TokenResult(true, email, accessToken, refreshToken, expiresIn, null);
        }
    }

    public OAuthService(OAuthConfig config, AuthService authService) {
        this.config = config != null ? config : OAuthConfig.load();
        this.authService = authService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public OAuthConfig getConfig() {
        return config;
    }

    /**
     * Đổi mã ủy quyền (Authorization Code) lấy bộ Token (Access Token & Refresh Token)
     */
    public TokenResult exchangeCode(String authCode, String redirectUri) {
        if (!config.isConfigured()) {
            return TokenResult.fail("Máy chủ chưa cấu hình client_secret.json cho Google OAuth.");
        }
        try {
            String form = "code=" + URLEncoder.encode(authCode, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(config.getClientSecret(), StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&grant_type=authorization_code";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getTokenUri()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return TokenResult.fail("Google từ chối mã xác thực (" + resp.statusCode() + "): " + resp.body());
            }

            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String accessToken = json.get("access_token").getAsString();
            String refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : "";
            int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;

            String email = fetchUserEmail(accessToken);
            if (email == null || email.isEmpty()) {
                email = "unknown@gmail.com";
            }

            return TokenResult.ok(email, accessToken, refreshToken, expiresIn);
        } catch (Exception e) {
            return TokenResult.fail("Lỗi kết nối khi đổi mã OAuth: " + e.getMessage());
        }
    }

    /**
     * Lấy địa chỉ Gmail từ Google UserInfo API
     */
    public String fetchUserEmail(String accessToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (json.has("email")) {
                    return json.get("email").getAsString().trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Làm mới Access Token thông qua Refresh Token
     */
    public String refreshAccessToken(String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty() || !config.isConfigured()) {
            return null;
        }
        try {
            String form = "client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(config.getClientSecret(), StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&grant_type=refresh_token";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getTokenUri()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                return json.get("access_token").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Lưu thông tin OAuth tokens của người dùng vào CSDL MySQL
     */
    public boolean saveTokens(String username, String gmailAddress, String accessToken, String refreshToken, int expiresIn) {
        if (username == null || gmailAddress == null || accessToken == null) return false;

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(Math.max(60, expiresIn));
        String sql = "INSERT INTO oauth_tokens (username, gmail_address, access_token, refresh_token, expires_at) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "gmail_address = VALUES(gmail_address), "
                + "access_token = VALUES(access_token), "
                + "refresh_token = IF(VALUES(refresh_token) != '', VALUES(refresh_token), refresh_token), "
                + "expires_at = VALUES(expires_at)";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, gmailAddress);
            stmt.setString(3, accessToken);
            stmt.setString(4, refreshToken != null ? refreshToken : "");
            stmt.setTimestamp(5, Timestamp.valueOf(expiresAt));
            stmt.executeUpdate();

            // Đồng bộ email vào bảng users
            if (authService != null) {
                authService.setUserEmail(username, gmailAddress);
            }
            return true;
        } catch (SQLException e) {
            System.err.println("[OAuthService] Lỗi lưu token: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy Access Token còn hiệu lực cho người dùng. Tự động gia hạn nếu token sắp hết hạn.
     */
    public synchronized String getValidAccessToken(String username) {
        if (username == null) return null;

        String selectSql = "SELECT access_token, refresh_token, expires_at, gmail_address FROM oauth_tokens WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                String accessToken = rs.getString("access_token");
                String refreshToken = rs.getString("refresh_token");
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                String gmail = rs.getString("gmail_address");

                // Kiểm tra token có hết hạn trong vòng 2 phút tới không
                long remainingMillis = expiresAt != null ? (expiresAt.getTime() - System.currentTimeMillis()) : 0;
                if (remainingMillis < 120_000 && refreshToken != null && !refreshToken.isEmpty()) {
                    // Token sắp hết hạn -> làm mới ngay
                    String newAccess = refreshAccessToken(refreshToken);
                    if (newAccess != null) {
                        saveTokens(username, gmail, newAccess, refreshToken, 3600);
                        return newAccess;
                    }
                }
                return accessToken;
            }
        } catch (SQLException e) {
            System.err.println("[OAuthService] Lỗi lấy access token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Kiểm tra người dùng đã liên kết tài khoản Gmail qua OAuth chưa
     */
    public boolean isLinked(String username) {
        if (username == null) return false;
        String sql = "SELECT id FROM oauth_tokens WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Lấy địa chỉ Gmail đã liên kết qua OAuth
     */
    public String getLinkedEmail(String username) {
        if (username == null) return null;
        String sql = "SELECT gmail_address FROM oauth_tokens WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("gmail_address");
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }
}
