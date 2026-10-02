package com.udpchat;

import com.udpchat.client.service.ClientService;
import com.udpchat.server.network.RequestHandler;
import com.udpchat.server.network.ServerNetwork;
import com.udpchat.server.service.AuthService;
import com.udpchat.server.service.FileService;
import com.udpchat.server.service.MailService;
import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationTest {

    private static ServerNetwork serverNetwork;
    private static AuthService authService;
    private static MailService mailService;
    private static FileService fileService;
    private static Thread serverThread;

    private static ClientService clientAlice;
    private static ClientService clientBob;

    private static final int PORT = 8888;
    private static final AtomicReference<String> bobPushedMailId = new AtomicReference<>();
    private static CountDownLatch newMailLatch = new CountDownLatch(1);

    private static String sentMailId;

    @BeforeAll
    public static void setUpAll() throws Exception {
        // Xóa sạch server-data để test từ đầu
        deleteDirectory(new File("server-data"));

        authService = new AuthService();
        mailService = new MailService();
        fileService = new FileService();

        RequestHandler handler = new RequestHandler(authService, mailService, fileService, System.out::println);
        serverNetwork = new ServerNetwork(handler, System.out::println);

        serverThread = new Thread(() -> serverNetwork.start(PORT));
        serverThread.setDaemon(true);
        serverThread.start();

        // Chờ server bind cổng UDP
        Thread.sleep(500);

        clientAlice = new ClientService("localhost", PORT);
        clientBob = new ClientService("localhost", PORT);

        clientBob.setOnNewMailNotification((sender, subject, mailId) -> {
            System.out.println("Bob nhận thông báo thư mới từ " + sender + ": " + subject + " (ID: " + mailId + ")");
            bobPushedMailId.set(mailId);
            newMailLatch.countDown();
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

        File userFile = new File("server-data/users/alice.txt");
        assertTrue(userFile.exists(), "User file for alice should exist");
        try {
            String content = java.nio.file.Files.readString(userFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains("password=secret123"), "User file should contain password");
            assertTrue(content.contains("ip="), "User file should contain client ip");
        } catch (java.io.IOException e) {
            fail("Failed to read user file: " + e.getMessage());
        }

        // Đăng ký trùng lặp phải thất bại
        String dupResp = clientAlice.register("alice", "secret123");
        assertTrue(dupResp.contains("ERROR"), "Duplicate register should fail: " + dupResp);

        // Đăng ký Bob
        String bobResp = clientBob.register("bob", "pass456");
        assertTrue(bobResp.contains("OK"), "Bob register should succeed: " + bobResp);
    }

    @Test
    @Order(2)
    public void testLogin() {
        // Sai mật khẩu
        String failLogin = clientAlice.login("alice", "wrongpass");
        assertTrue(failLogin.contains("ERROR"), "Login with wrong password should fail");

        // Đăng nhập đúng Alice
        String loginResp = clientAlice.login("alice", "secret123");
        assertTrue(loginResp.contains("OK"), "Alice login should succeed: " + loginResp);
        assertTrue(clientAlice.isLoggedIn());

        // Đăng nhập đúng Bob
        String bobLogin = clientBob.login("bob", "pass456");
        assertTrue(bobLogin.contains("OK"), "Bob login should succeed: " + bobLogin);
        assertTrue(clientBob.isLoggedIn());

        assertEquals(2, authService.getOnlineCount(), "There should be 2 online sessions");
    }

    @Test
    @Order(3)
    public void testSendMailAndPushNotification() throws Exception {
        Email email = new Email("alice", List.of("bob"), "Họp kế hoạch Q3", "Chào Bob, chúng ta cần họp vào 14h hôm nay.");
        boolean sent = clientAlice.sendMail(email);
        assertTrue(sent, "Alice sending mail to Bob should succeed");
        sentMailId = email.getId();

        // Chờ Bob nhận được push NEW_MAIL từ server qua UDP listener
        boolean received = newMailLatch.await(3, TimeUnit.SECONDS);
        assertTrue(received, "Bob should receive real-time NEW_MAIL push notification within 3s");
        assertEquals(sentMailId, bobPushedMailId.get());

        // Bob kiểm tra danh sách Hộp thư đến (INBOX)
        ClientService.MailListResult inboxResult = clientBob.listMails(MailFolder.INBOX, 1, 20);
        assertNotNull(inboxResult.emails);
        assertFalse(inboxResult.emails.isEmpty(), "Bob's inbox should contain at least 1 email");
        assertEquals(1, inboxResult.unreadInbox, "Bob should have 1 unread email");

        Email receivedMail = inboxResult.emails.get(0);
        assertEquals("alice", receivedMail.getSender());
        assertEquals("Họp kế hoạch Q3", receivedMail.getSubject());
        assertFalse(receivedMail.isRead(), "Incoming email should be unread initially");

        // Alice kiểm tra danh sách Đã gửi (SENT)
        ClientService.MailListResult sentResult = clientAlice.listMails(MailFolder.SENT, 1, 20);
        assertFalse(sentResult.emails.isEmpty(), "Alice's sent folder should have 1 email");
        assertEquals("Họp kế hoạch Q3", sentResult.emails.get(0).getSubject());
    }

    @Test
    @Order(4)
    public void testReadMailAndMarkRead() {
        assertNotNull(sentMailId);

        // Bob đọc chi tiết thư
        Email fullMail = clientBob.readMail(sentMailId);
        assertNotNull(fullMail, "Bob should be able to read full email");
        assertEquals("Họp kế hoạch Q3", fullMail.getSubject());
        assertEquals("Chào Bob, chúng ta cần họp vào 14h hôm nay.", fullMail.getBody());

        // Đọc thư sẽ tự động chuyển trạng thái read = true trên server
        ClientService.MailListResult inboxResult = clientBob.listMails(MailFolder.INBOX, 1, 20);
        assertEquals(0, inboxResult.unreadInbox, "Unread count should become 0 after reading");
    }

    @Test
    @Order(5)
    public void testStarMail() {
        assertNotNull(sentMailId);

        // Bob gắn sao cho thư
        boolean starred = clientBob.toggleStar(sentMailId, true);
        assertTrue(starred, "Bob toggling star should succeed");

        // Kiểm tra thư mục STARRED
        ClientService.MailListResult starredResult = clientBob.listMails(MailFolder.STARRED, 1, 20);
        assertFalse(starredResult.emails.isEmpty(), "Starred folder should contain the email");
        assertEquals(sentMailId, starredResult.emails.get(0).getId());
    }

    @Test
    @Order(6)
    public void testReplyMail() {
        assertNotNull(sentMailId);

        // Bob trả lời Alice
        Email reply = new Email("bob", List.of("alice"), "Re: Họp kế hoạch Q3", "OK Alice, tôi sẽ tham gia đúng giờ.");
        reply.setReplyToId(sentMailId);
        boolean sentReply = clientBob.sendMail(reply);
        assertTrue(sentReply, "Bob reply should succeed");

        // Alice kiểm tra Inbox
        ClientService.MailListResult aliceInbox = clientAlice.listMails(MailFolder.INBOX, 1, 20);
        assertFalse(aliceInbox.emails.isEmpty(), "Alice should have the reply in inbox");
        Email threadRep = aliceInbox.emails.get(0);
        assertEquals("Re: Họp kế hoạch Q3", threadRep.getSubject());
        assertEquals(2, threadRep.getThreadMessageCount(), "Thread should group 2 messages together");
        assertTrue(threadRep.getThreadParticipants().contains("bob"), "Participants should include bob");

        // Alice đọc toàn bộ luồng hội thoại
        List<Email> threadMessages = clientAlice.getThreadMessages(threadRep.getThreadId());
        assertEquals(2, threadMessages.size(), "getThreadMessages should return exactly 2 emails");
        assertEquals("Họp kế hoạch Q3", threadMessages.get(0).getSubject());
        assertEquals("Re: Họp kế hoạch Q3", threadMessages.get(1).getSubject());
    }

    @Test
    @Order(7)
    public void testConversationThreadingMultiTurn() {
        // Alice phản hồi tiếp cho Bob trong cùng luồng
        ClientService.MailListResult aliceInbox = clientAlice.listMails(MailFolder.INBOX, 1, 20);
        assertFalse(aliceInbox.emails.isEmpty());
        Email parentThread = aliceInbox.emails.get(0);

        Email reply2 = new Email("alice", List.of("bob"), "Re: Họp kế hoạch Q3", "Tuyệt vời, tôi đã chuẩn bị slide.");
        reply2.setReplyToId(parentThread.getId());
        reply2.setThreadId(parentThread.getThreadId());
        boolean sentReply2 = clientAlice.sendMail(reply2);
        assertTrue(sentReply2, "Alice sending second reply should succeed");

        // Bob kiểm tra Inbox: Chỉ hiển thị duy nhất 1 luồng hội thoại gộp cả 3 thư
        ClientService.MailListResult bobInbox = clientBob.listMails(MailFolder.INBOX, 1, 20);
        assertFalse(bobInbox.emails.isEmpty(), "Bob inbox should have the conversation thread");
        Email bobThread = bobInbox.emails.get(0);
        assertEquals(3, bobThread.getThreadMessageCount(), "Thread should now contain 3 messages");

        // Bob đọc luồng hội thoại: Cả 3 thư theo đúng thứ tự thời gian
        List<Email> fullThread = clientBob.getThreadMessages(bobThread.getThreadId());
        assertEquals(3, fullThread.size(), "Bob should get all 3 messages in the conversation");
        assertEquals("alice", fullThread.get(0).getSender());
        assertEquals("bob", fullThread.get(1).getSender());
        assertEquals("alice", fullThread.get(2).getSender());
    }

    @Test
    @Order(8)
    public void testSaveDraftAndSearch() {
        // Alice lưu bản nháp
        Email draft = new Email("alice", List.of("bob"), "Dự thảo báo cáo UDP", "Nội dung đang viết dở...");
        boolean saved = clientAlice.saveDraft(draft);
        assertTrue(saved, "Saving draft should succeed");

        // Kiểm tra thư mục DRAFTS
        ClientService.MailListResult drafts = clientAlice.listMails(MailFolder.DRAFTS, 1, 20);
        assertFalse(drafts.emails.isEmpty(), "Alice should have 1 draft");
        assertEquals("Dự thảo báo cáo UDP", drafts.emails.get(0).getSubject());

        // Tìm kiếm theo từ khóa
        List<Email> searchResults = clientAlice.searchMail("báo cáo");
        assertFalse(searchResults.isEmpty(), "Search for 'báo cáo' should find the draft");
    }

    @Test
    @Order(9)
    public void testAttachmentUploadAndDownload() throws Exception {
        // Tạo tệp test
        File tempDir = new File("target/test-files");
        tempDir.mkdirs();
        File uploadFile = new File(tempDir, "dinh_kem_hop.txt");
        String fileContent = "Tai lieu dinh kem cuoc hop quan trong qua giao thuc UDP. ".repeat(80);
        Files.writeString(uploadFile.toPath(), fileContent);

        // Alice tải lên tệp đính kèm qua UDP
        boolean uploaded = clientAlice.uploadAttachment(uploadFile, null);
        assertTrue(uploaded, "Attachment upload should succeed via UDP");

        // Alice gửi mail có đính kèm tệp
        Attachment att = new Attachment(
                UUID.randomUUID().toString(),
                uploadFile.getName(),
                uploadFile.length(),
                "DOCUMENT",
                "alice",
                "2026-10-02 11:30:00"
        );

        Email mailWithAtt = new Email("alice", List.of("bob"), "Tài liệu đính kèm", "Gửi Bob tài liệu cuộc họp.");
        mailWithAtt.setAttachments(List.of(att));
        boolean sent = clientAlice.sendMail(mailWithAtt);
        assertTrue(sent, "Sending email with attachment should succeed");

        // Đảm bảo server đã lưu xong file đính kèm
        File serverFile = new File("server-data/attachments/" + uploadFile.getName());
        boolean serverFileReady = false;
        for (int i = 0; i < 30; i++) {
            if (serverFile.exists() && serverFile.length() == uploadFile.length()) {
                serverFileReady = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(serverFileReady, "Server should have finished saving attachment");

        // Bob tải xuống tệp đính kèm qua UDP
        File downloadDir = new File("target/test-downloads");
        downloadDir.mkdirs();
        boolean downloaded = clientBob.downloadAttachment(uploadFile.getName(), downloadDir, null);
        assertTrue(downloaded, "Bob downloading attachment should succeed");

        File downloadedFile = new File(downloadDir, uploadFile.getName());
        assertTrue(downloadedFile.exists(), "Downloaded attachment should exist on disk");
        assertEquals(fileContent, Files.readString(downloadedFile.toPath()), "Downloaded content must match");
    }

    @Test
    @Order(10)
    public void testDeleteMail() {
        assertNotNull(sentMailId);

        // Bob chuyển thư vào Thùng rác
        boolean movedToTrash = clientBob.deleteMail(sentMailId, false);
        assertTrue(movedToTrash, "Moving email to trash should succeed");

        // Kiểm tra trong TRASH có thư
        ClientService.MailListResult trash = clientBob.listMails(MailFolder.TRASH, 1, 20);
        assertFalse(trash.emails.isEmpty(), "Trash should contain the deleted email");

        // Xóa vĩnh viễn
        boolean permDeleted = clientBob.deleteMail(sentMailId, true);
        assertTrue(permDeleted, "Permanent delete should succeed");
    }

    @Test
    @Order(11)
    public void testLogout() {
        String logoutAlice = clientAlice.logout();
        assertTrue(logoutAlice.contains("OK"), "Alice logout should succeed");
        assertFalse(clientAlice.isLoggedIn());

        String logoutBob = clientBob.logout();
        assertTrue(logoutBob.contains("OK"), "Bob logout should succeed");
        assertFalse(clientBob.isLoggedIn());

        assertEquals(0, authService.getOnlineCount(), "Online sessions should be 0 after logout");
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
