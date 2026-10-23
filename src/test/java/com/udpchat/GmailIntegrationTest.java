package com.udpchat;

import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.OAuthConfig;
import com.udpchat.server.service.OAuthService;
import com.udpchat.server.service.SmtpDeliveryService;
import com.udpchat.shared.model.Email;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class GmailIntegrationTest {

    @Test
    public void testOAuthConfigAndAuthUrl() {
        OAuthConfig config = new OAuthConfig();
        config.setClientId("test-client-id-12345");
        config.setClientSecret("test-secret-67890");
        assertTrue(config.isConfigured());
        assertEquals("test-client-id-12345", config.getClientId());
        assertEquals("test-secret-67890", config.getClientSecret());

        String authUrl = config.buildAuthUrl("http://127.0.0.1:8888/callback", "test_user");
        assertNotNull(authUrl);
        assertTrue(authUrl.contains("test-client-id-12345"));
        assertTrue(authUrl.contains("http%3A%2F%2F127.0.0.1%3A8888%2Fcallback"));
        assertTrue(authUrl.contains("response_type=code"));
    }

    @Test
    public void testAuthServiceUserEmailMapping() throws Exception {
        AuthService authService = new AuthService();
        String testUser = "test_mail_user_" + System.currentTimeMillis();

        authService.register(testUser, "pass123");
        assertNull(authService.getUserEmail(testUser));

        boolean updated = authService.setUserEmail(testUser, "real_person@gmail.com");
        assertTrue(updated);
        assertEquals("real_person@gmail.com", authService.getUserEmail(testUser));

        // Test mapping lookup
        Map<String, String> map = authService.getAllUserEmails();
        assertTrue(map.containsKey(testUser));
        assertEquals("real_person@gmail.com", map.get(testUser));

        // Direct email lookup returns itself
        assertEquals("direct@gmail.com", authService.getUserEmail("direct@gmail.com"));

        // Reverse lookup: email -> username
        assertEquals(testUser, authService.getUsernameByEmail("real_person@gmail.com"));

        // Cleanup
        try (var conn = com.udpchat.server.database.DatabaseManager.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM users WHERE username = ?")) {
            stmt.setString(1, testUser);
            stmt.executeUpdate();
        }
    }

    @Test
    public void testEmailGmailMessageId() {
        Email email = new Email();
        assertNull(email.getGmailMessageId());

        email.setGmailMessageId("<unique-12345@gmail.com>");
        assertEquals("<unique-12345@gmail.com>", email.getGmailMessageId());
    }

    @Test
    public void testSmtpDeliveryServiceUnlinkedSafeBypass() throws Exception {
        AuthService authService = new AuthService();
        OAuthConfig config = new OAuthConfig();
        OAuthService oAuthService = new OAuthService(config, authService);

        List<String> logs = new ArrayList<>();
        SmtpDeliveryService deliveryService = new SmtpDeliveryService(oAuthService, logs::add);

        Email email = new Email("unlinked_user", List.of("bob@gmail.com"), "Tiêu đề test", "Nội dung test");

        CompletableFuture<Boolean> future = deliveryService.sendViaSmtp(email, Collections.emptyMap());
        assertNotNull(future);
        Boolean result = future.get();
        assertFalse(result, "Người gửi chưa liên kết OAuth phải được bypass an toàn và trả về false mà không gọi mạng");

        assertTrue(logs.stream().anyMatch(l -> l.contains("chưa liên kết Google OAuth")));

        deliveryService.shutdown();
    }

    @Test
    public void testSmtpDeliveryServiceNullEmailSafeBypass() throws Exception {
        SmtpDeliveryService deliveryService = new SmtpDeliveryService(null, null);
        CompletableFuture<Boolean> future = deliveryService.sendViaSmtp(null, Collections.emptyMap());
        assertNotNull(future);
        assertFalse(future.get());
        deliveryService.shutdown();
    }
}
