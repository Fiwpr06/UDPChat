package com.udpchat.client.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hỗ trợ luồng OAuth 2.0 Loopback trên Client: mở trình duyệt và bắt authorization code qua HTTP Server cục bộ
 */
public class OAuthFlowHelper {

    public static class OAuthCallbackResult {
        public final String authCode;
        public final String redirectUri;

        public OAuthCallbackResult(String authCode, String redirectUri) {
            this.authCode = authCode;
            this.redirectUri = redirectUri;
        }
    }

    public static class OAuthSession {
        private final HttpServer server;
        private final String redirectUri;
        private final CompletableFuture<OAuthCallbackResult> future = new CompletableFuture<>();

        public OAuthSession(HttpServer server, String redirectUri) {
            this.server = server;
            this.redirectUri = redirectUri;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public CompletableFuture<OAuthCallbackResult> getFuture() {
            return future;
        }

        public void cancel() {
            if (!future.isDone()) {
                future.cancel(true);
            }
            stopServer();
        }

        private void stopServer() {
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
                server.stop(0);
            }).start();
        }
    }

    /**
     * Khởi tạo máy chủ HTTP lắng nghe callback trên cổng ngẫu nhiên 127.0.0.1
     */
    public static OAuthSession prepareSession() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String redirectUri = "http://127.0.0.1:" + port + "/callback";

        OAuthSession session = new OAuthSession(server, redirectUri);

        server.createContext("/callback", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQueryParams(query);
                String code = params.get("code");
                String error = params.get("error");

                String html;
                if (code != null && !code.isEmpty()) {
                    html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Xác thực thành công</title></head>"
                            + "<body style='font-family: -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif; "
                            + "text-align: center; padding: 60px 20px; background: #0B0F19; color: #F1F5F9;'>"
                            + "<div style='display:inline-block; padding:30px 40px; background:#111827; border:1px solid #1E293B; border-radius:12px; box-shadow:0 10px 25px rgba(0,0,0,0.5);'>"
                            + "<div style='font-size: 48px; margin-bottom: 12px;'>✓</div>"
                            + "<h2 style='color: #22C55E; margin: 0 0 10px 0;'>Liên kết Gmail thành công!</h2>"
                            + "<p style='color: #94A3B8; font-size: 15px;'>Tài khoản Google của bạn đã được kết nối với UDP Mail Chat.</p>"
                            + "<p style='color: #64748B; font-size: 13px; margin-top: 20px;'>Bạn có thể đóng tab trình duyệt này và quay trở lại ứng dụng.</p>"
                            + "</div></body></html>";
                    session.future.complete(new OAuthCallbackResult(code, redirectUri));
                } else {
                    html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Xác thực thất bại</title></head>"
                            + "<body style='font-family: sans-serif; text-align: center; padding: 60px 20px; background: #0B0F19; color: #F1F5F9;'>"
                            + "<div style='display:inline-block; padding:30px 40px; background:#111827; border:1px solid #1E293B; border-radius:12px;'>"
                            + "<h2 style='color: #EF4444;'>Xác thực không thành công</h2>"
                            + "<p style='color: #94A3B8;'>" + (error != null ? error : "Không tìm thấy mã ủy quyền từ Google.") + "</p>"
                            + "</div></body></html>";
                    session.future.completeExceptionally(new RuntimeException(error != null ? error : "Không có mã code"));
                }

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }

                session.stopServer();
            }
        });

        server.setExecutor(null);
        server.start();
        return session;
    }

    /**
     * Mở trình duyệt mặc định của hệ điều hành với đường dẫn cấp quyền
     */
    public static void openBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            new Thread(() -> {
                try {
                    Desktop.getDesktop().browse(URI.create(url));
                } catch (Exception e) {
                    System.err.println("[OAuthFlowHelper] Không thể mở trình duyệt: " + e.getMessage());
                }
            }).start();
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            }
        }
        return params;
    }
}
