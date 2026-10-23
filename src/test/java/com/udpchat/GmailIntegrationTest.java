package com.udpchat;

import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.GmailConfig;
import com.udpchat.server.service.GmailGateway;
import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class GmailIntegrationTest {

    @Test
    public void testGmailConfigDefaultsAndSaveLoad() {
        GmailConfig config = new GmailConfig();
        assertNotNull(config.getSmtpHost());
        assertEquals(587, config.getSmtpPort());
        assertNotNull(config.getImapHost());
        assertEquals(993, config.getImapPort());

        // Test configuration setters
        config.setGmailAddress("testuser@gmail.com");
        config.setAppPassword("abcd efgh ijkl mnop");
        config.setEnabled(true);
        config.save();

        // Load again in a fresh instance
        GmailConfig reloaded = new GmailConfig();
        assertTrue(reloaded.isEnabled());
        assertEquals("testuser@gmail.com", reloaded.getGmailAddress());
        assertEquals("abcd efgh ijkl mnop", reloaded.getAppPassword());
        assertTrue(reloaded.isConfigured());

        // Restore to disabled for safety
        reloaded.setEnabled(false);
        reloaded.save();
        assertFalse(new GmailConfig().isEnabled());
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
    public void testGmailGatewayDisabledBypass() throws Exception {
        GmailConfig config = new GmailConfig();
        config.setEnabled(false);

        List<String> logs = new ArrayList<>();
        GmailGateway gateway = new GmailGateway(config, logs::add);

        Email email = new Email("alice", List.of("bob@gmail.com"), "Tiêu đề test", "Nội dung test");
        Map<String, String> userEmails = Map.of("bob", "bob@gmail.com");

        // Khi config disabled, sendViaSmtp phải hoàn thành ngay lập tức và trả về false mà không gọi network
        CompletableFuture<Boolean> future = gateway.sendViaSmtp(email, userEmails);
        assertNotNull(future);
        Boolean result = future.get();
        assertFalse(result, "Khi disabled, sendViaSmtp phải trả về false an toàn mà không throw exception");

        gateway.shutdown();
    }
}
