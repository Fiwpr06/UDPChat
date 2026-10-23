package com.udpchat;

import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.GmailConfig;
import com.udpchat.server.service.GmailGateway;
import com.udpchat.shared.model.Email;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class GmailIntegrationTest {

    @Test
    public void testServerGmailConfigDefaultsAndSaveLoad() {
        GmailConfig config = new GmailConfig();
        assertNotNull(config.getSmtpHost());
        assertEquals(587, config.getSmtpPort());
        assertNotNull(config.getImapHost());
        assertEquals(993, config.getImapPort());

        // Test configuration setters and clean app password
        config.setGmailAddress("admin@gmail.com");
        config.setAppPassword("abcd efgh ijkl mnop");
        assertEquals("abcdefghijklmnop", config.getCleanAppPassword());
        config.setEnabled(true);
        config.save();

        // Load again in a fresh instance
        GmailConfig reloaded = new GmailConfig();
        reloaded.load();
        assertTrue(reloaded.isEnabled());
        assertEquals("admin@gmail.com", reloaded.getGmailAddress());
        // App password spaces are automatically stripped for safety
        assertEquals("abcdefghijklmnop", reloaded.getAppPassword());
        assertTrue(reloaded.isConfigured());

        // Restore to disabled for safety
        reloaded.setEnabled(false);
        reloaded.save();

        GmailConfig verifyDisabled = new GmailConfig();
        verifyDisabled.load();
        assertFalse(verifyDisabled.isEnabled());
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
    public void testServerGmailGatewayDisabledBypass() throws Exception {
        GmailConfig config = new GmailConfig();
        config.setEnabled(false);

        List<String> logs = new ArrayList<>();
        GmailGateway gateway = new GmailGateway(config, logs::add);

        Email email = new Email("alice", List.of("bob@gmail.com"), "Tiêu đề test", "Nội dung test");

        CompletableFuture<Boolean> future = gateway.sendViaSmtp(email, Collections.emptyMap());
        assertNotNull(future);
        Boolean result = future.get();
        assertFalse(result, "Khi disabled, sendViaSmtp phải trả về false an toàn mà không gọi mạng");

        // Test validation of testConnection
        GmailGateway.ConnectionTestResult resEmpty = gateway.testConnection("", "").get();
        assertFalse(resEmpty.isSuccess());

        gateway.shutdown();
    }
}
