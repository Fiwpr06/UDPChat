package com.udpchat;

import com.udpchat.client.service.ClientGmailConfig;
import com.udpchat.client.service.ClientGmailGateway;
import com.udpchat.server.service.AuthService;
import com.udpchat.shared.model.Email;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class GmailIntegrationTest {

    @Test
    public void testClientGmailConfigDefaultsAndSaveLoad() {
        ClientGmailConfig config = new ClientGmailConfig();
        assertNotNull(config.getSmtpHost());
        assertEquals(587, config.getSmtpPort());
        assertNotNull(config.getImapHost());
        assertEquals(993, config.getImapPort());

        String testUsername = "alice_test_" + System.currentTimeMillis();

        // Test configuration setters
        config.setGmailAddress("alice@gmail.com");
        config.setAppPassword("abcd efgh ijkl mnop");
        assertEquals("abcdefghijklmnop", config.getCleanAppPassword());
        config.setEnabled(true);
        config.save(testUsername);

        // Load again in a fresh instance
        ClientGmailConfig reloaded = new ClientGmailConfig();
        reloaded.load(testUsername);
        assertTrue(reloaded.isEnabled());
        assertEquals("alice@gmail.com", reloaded.getGmailAddress());
        // App password spaces are automatically stripped for safety
        assertEquals("abcdefghijklmnop", reloaded.getAppPassword());
        assertTrue(reloaded.isConfigured());

        // Restore to disabled
        reloaded.setEnabled(false);
        reloaded.save(testUsername);

        ClientGmailConfig verifyDisabled = new ClientGmailConfig();
        verifyDisabled.load(testUsername);
        assertFalse(verifyDisabled.isEnabled());

        // Cleanup
        try {
            Files.deleteIfExists(Path.of("client-cache", "gmail-config-" + testUsername + ".properties"));
        } catch (Exception ignored) {}
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

        // Cleanup
        Files.deleteIfExists(Path.of("server-data", "users", testUser + ".txt"));
    }

    @Test
    public void testEmailGmailMessageId() {
        Email email = new Email();
        assertNull(email.getGmailMessageId());

        email.setGmailMessageId("<unique-12345@gmail.com>");
        assertEquals("<unique-12345@gmail.com>", email.getGmailMessageId());
    }

    @Test
    public void testClientGmailGatewayDisabledBypass() throws Exception {
        ClientGmailConfig config = new ClientGmailConfig();
        config.setEnabled(false);

        List<String> logs = new ArrayList<>();
        ClientGmailGateway gateway = new ClientGmailGateway(config);

        Email email = new Email("alice", List.of("bob@gmail.com"), "Tiêu đề test", "Nội dung test");

        CompletableFuture<ClientGmailGateway.SmtpSendResult> future = gateway.sendDirectViaSmtp(email, Collections.emptyList(), logs::add);
        assertNotNull(future);
        ClientGmailGateway.SmtpSendResult result = future.get();
        assertFalse(result.isSuccess(), "Khi disabled, sendDirectViaSmtp phải trả về false an toàn mà không gọi mạng");
        assertEquals("Chưa bật hoặc chưa cấu hình Gmail", result.getMessage());

        gateway.shutdown();
    }
}
