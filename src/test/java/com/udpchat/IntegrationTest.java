package com.udpchat;

import com.udpchat.client.service.ClientService;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MessageService;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationTest {

    private static ServerNetwork serverNetwork;
    private static AuthService authService;
    private static MessageService messageService;
    private static FileService fileService;
    private static Thread serverThread;

    private static ClientService clientAlice;
    private static ClientService clientBob;

    private static final int PORT = 8888;
    private static final AtomicReference<String> bobReceivedMessage = new AtomicReference<>();
    private static CountDownLatch messageLatch = new CountDownLatch(1);

    @BeforeAll
    public static void setUpAll() throws Exception {
        // Clean up server-data for clean test run
        deleteDirectory(new File("server-data"));

        authService = new AuthService();
        messageService = new MessageService();
        fileService = new FileService();

        RequestHandler handler = new RequestHandler(authService, messageService, fileService, System.out::println);
        serverNetwork = new ServerNetwork(handler, System.out::println);

        serverThread = new Thread(() -> serverNetwork.start(PORT));
        serverThread.setDaemon(true);
        serverThread.start();

        // Give server a moment to bind
        Thread.sleep(500);

        clientAlice = new ClientService("localhost", PORT);
        clientBob = new ClientService("localhost", PORT);

        clientBob.setOnIncomingMessage(msg -> {
            System.out.println("Bob received push message: " + msg);
            bobReceivedMessage.set(msg);
            messageLatch.countDown();
        });
    }

    @AfterAll
    public static void tearDownAll() {
        if (clientAlice != null) clientAlice.close();
        if (clientBob != null) clientBob.close();
        if (serverNetwork != null) serverNetwork.stop();
    }

    @Test
    @Order(1)
    public void testRegister() {
        String resp = clientAlice.register("alice", "secret123");
        assertTrue(resp.contains("OK"), "Alice register should succeed: " + resp);

        // Check user file created
        File userFile = new File("server-data/users/alice.txt");
        assertTrue(userFile.exists(), "User file for alice should exist");

        // Duplicate register should fail
        String dupResp = clientAlice.register("alice", "secret123");
        assertTrue(dupResp.contains("ERROR"), "Duplicate register should fail: " + dupResp);

        // Register Bob
        String bobResp = clientBob.register("bob", "pass456");
        assertTrue(bobResp.contains("OK"), "Bob register should succeed: " + bobResp);
    }

    @Test
    @Order(2)
    public void testLogin() {
        // Wrong password should fail
        String failLogin = clientAlice.login("alice", "wrongpass");
        assertTrue(failLogin.contains("ERROR"), "Login with wrong password should fail");

        // Correct login
        String loginResp = clientAlice.login("alice", "secret123");
        assertTrue(loginResp.contains("OK"), "Alice login should succeed: " + loginResp);
        assertTrue(clientAlice.isLoggedIn());

        // Login Bob
        String bobLogin = clientBob.login("bob", "pass456");
        assertTrue(bobLogin.contains("OK"), "Bob login should succeed: " + bobLogin);
        assertTrue(clientBob.isLoggedIn());

        assertEquals(2, authService.getOnlineCount(), "There should be 2 online sessions");
    }

    @Test
    @Order(3)
    public void testSendMessageAndForwarding() throws Exception {
        String testContent = "Hello Bob, this is Alice via UDP!";
        String sendResp = clientAlice.sendMessage(testContent);
        assertTrue(sendResp.contains("OK"), "Alice message should be sent: " + sendResp);

        // Wait for Bob's listener to receive the pushed message
        boolean received = messageLatch.await(3, TimeUnit.SECONDS);
        assertTrue(received, "Bob should receive the forwarded message within 3 seconds");
        assertNotNull(bobReceivedMessage.get());
        assertTrue(bobReceivedMessage.get().contains("alice: " + testContent));

        // Check messages.log on server
        Path logPath = Paths.get("server-data", "messages", "messages.log");
        assertTrue(Files.exists(logPath), "messages.log should exist on server");
        String logContent = Files.readString(logPath);
        assertTrue(logContent.contains("alice"));
        assertTrue(logContent.contains(testContent));
        // Verify format: IP|Tên|TĐ|Nội dung
        String[] lines = logContent.trim().split("\r?\n");
        assertTrue(lines.length >= 1);
        String lastLine = lines[lines.length - 1];
        String[] parts = lastLine.split("\\|", 4);
        assertEquals(4, parts.length, "Message log should have 4 fields: IP|Tên|TĐ|Nội dung");
        assertEquals("alice", parts[1]);
        assertEquals(testContent, parts[3]);
    }

    @Test
    @Order(4)
    public void testFileUploadAndDownload() throws Exception {
        // Create a test file
        File tempDir = new File("target/test-files");
        tempDir.mkdirs();
        File uploadFile = new File(tempDir, "sample_document.txt");
        String sampleText = "UDP File Transfer Test Content with multiple chunks. ".repeat(100);
        Files.writeString(uploadFile.toPath(), sampleText);

        // Alice uploads file
        String uploadResp = clientAlice.uploadFile(uploadFile);
        assertTrue(uploadResp.contains("OK"), "Upload should succeed: " + uploadResp);

        // Verify file stored on server (wait up to 3s for server thread to write to disk)
        File serverFile = new File("server-data/files/sample_document.txt");
        boolean serverFileFound = false;
        for (int i = 0; i < 30; i++) {
            if (serverFile.exists() && serverFile.length() == uploadFile.length()) {
                serverFileFound = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(serverFileFound, "File should be saved on server in server-data/files");
        assertEquals(uploadFile.length(), serverFile.length(), "Uploaded file size should match");

        // Bob downloads file
        File downloadDir = new File("target/test-downloads");
        downloadDir.mkdirs();
        String downloadResp = clientBob.downloadFile("sample_document.txt", downloadDir);
        assertTrue(downloadResp.contains("OK"), "Download should succeed: " + downloadResp);

        File downloadedFile = new File(downloadDir, "sample_document.txt");
        boolean downloadFileFound = false;
        for (int i = 0; i < 30; i++) {
            if (downloadedFile.exists() && downloadedFile.length() == uploadFile.length()) {
                downloadFileFound = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(downloadFileFound, "Downloaded file should exist");
        assertEquals(sampleText, Files.readString(downloadedFile.toPath()), "Downloaded content should match original");
    }

    @Test
    @Order(5)
    public void testLogout() {
        String logoutAlice = clientAlice.logout();
        assertTrue(logoutAlice.contains("OK"), "Alice logout should succeed");
        assertFalse(clientAlice.isLoggedIn());

        String logoutBob = clientBob.logout();
        assertTrue(logoutBob.contains("OK"), "Bob logout should succeed");
        assertFalse(clientBob.isLoggedIn());

        assertEquals(0, authService.getOnlineCount(), "Online sessions should be 0 after both logout");
    }

    private static void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDirectory(f);
                }
            }
        }
        dir.delete();
    }
}
