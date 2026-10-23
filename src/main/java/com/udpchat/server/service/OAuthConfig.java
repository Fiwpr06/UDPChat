package com.udpchat.server.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Đọc và quản lý thông tin Client ID & Client Secret của Google OAuth 2.0 (Desktop App)
 */
public class OAuthConfig {
    private String clientId = "";
    private String clientSecret = "";
    private String authUri = "https://accounts.google.com/o/oauth2/auth";
    private String tokenUri = "https://oauth2.googleapis.com/token";

    public static OAuthConfig load() {
        OAuthConfig config = new OAuthConfig();
        config.reload();
        return config;
    }

    /**
     * Tự động quét và nạp cấu hình OAuth từ các vị trí tệp phổ biến và classpath.
     */
    public boolean reload() {
        List<Path> candidatePaths = new ArrayList<>();

        // 1. Các đường dẫn ưu tiên trực tiếp
        candidatePaths.add(Paths.get("src", "main", "resources", "client_secret.json"));
        candidatePaths.add(Paths.get("server-data", "client_secret.json"));
        candidatePaths.add(Paths.get("client_secret.json"));
        candidatePaths.add(Paths.get("target", "classes", "client_secret.json"));

        // 2. Quét mở rộng mọi tệp dạng client_secret*.json trong các thư mục
        String[] searchDirs = { "src/main/resources", ".", "server-data", "target/classes" };
        for (String dir : searchDirs) {
            Path p = Paths.get(dir);
            if (Files.exists(p) && Files.isDirectory(p)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(p, "client_secret*.json")) {
                    for (Path entry : stream) {
                        if (!candidatePaths.contains(entry)) {
                            candidatePaths.add(entry);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3. Duyệt kiểm tra các tệp trên hệ thống tệp
        for (Path path : candidatePaths) {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    if (this.parseJson(in) && this.isConfigured()) {
                        System.out.println("[OAuthConfig] ✓ Đã nạp thành công cấu hình Google Client từ: " + path);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. Fallback: nạp từ classpath: /client_secret.json
        try (InputStream in = OAuthConfig.class.getResourceAsStream("/client_secret.json")) {
            if (in != null) {
                if (this.parseJson(in) && this.isConfigured()) {
                    System.out.println("[OAuthConfig] ✓ Đã nạp cấu hình Google Client từ classpath.");
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    private boolean parseJson(InputStream in) {
        try {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject details = null;
            if (root.has("installed")) {
                details = root.getAsJsonObject("installed");
            } else if (root.has("web")) {
                details = root.getAsJsonObject("web");
            } else {
                details = root;
            }

            if (details != null) {
                if (details.has("client_id")) {
                    this.clientId = details.get("client_id").getAsString().trim();
                }
                if (details.has("client_secret")) {
                    this.clientSecret = details.get("client_secret").getAsString().trim();
                }
                if (details.has("auth_uri")) {
                    this.authUri = details.get("auth_uri").getAsString().trim();
                }
                if (details.has("token_uri")) {
                    this.tokenUri = details.get("token_uri").getAsString().trim();
                }
                return isConfigured();
            }
        } catch (Exception e) {
            System.err.println("[OAuthConfig] Lỗi đọc client_secret.json: " + e.getMessage());
        }
        return false;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty() && !clientId.contains("YOUR_CLIENT_ID")
                && clientSecret != null && !clientSecret.isEmpty() && !clientSecret.contains("YOUR_CLIENT_SECRET");
    }

    /**
     * Tạo đường dẫn cấp quyền Google OAuth 2.0 để mở trên trình duyệt của máy khách
     */
    public String buildAuthUrl(String redirectUri, String state) {
        if (!isConfigured()) return "";
        try {
            String scope = URLEncoder.encode("https://mail.google.com/ email profile", StandardCharsets.UTF_8);
            String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
            String encodedState = (state != null && !state.isEmpty()) ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "";

            return authUri + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + encodedRedirect
                    + "&response_type=code"
                    + "&scope=" + scope
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + encodedState;
        } catch (Exception e) {
            return "";
        }
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getAuthUri() { return authUri; }
    public String getTokenUri() { return tokenUri; }
}
