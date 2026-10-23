package com.udpchat.server.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        // 1. Thử đọc từ server-data/client_secret.json
        Path externalPath = Paths.get("server-data", "client_secret.json");
        if (Files.exists(externalPath)) {
            try (InputStream in = Files.newInputStream(externalPath)) {
                if (config.parseJson(in)) {
                    return config;
                }
            } catch (Exception ignored) {}
        }

        // 2. Thử đọc từ classpath: /client_secret.json
        try (InputStream in = OAuthConfig.class.getResourceAsStream("/client_secret.json")) {
            if (in != null) {
                config.parseJson(in);
            }
        } catch (Exception ignored) {}

        return config;
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
