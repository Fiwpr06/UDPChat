package com.udpchat;

import com.udpchat.client.pop3.Pop3Client;
import com.udpchat.client.smtp.SmtpClient;
import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;
import com.udpchat.common.model.User;
import com.udpchat.database.DatabaseManager;
import com.udpchat.database.EmailDao;
import com.udpchat.database.UserDao;
import com.udpchat.server.pop3.Pop3Server;
import com.udpchat.server.service.GmailForwarder;
import com.udpchat.server.smtp.SmtpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SmtpPop3IntegrationTest {

    private static final int SMTP_TEST_PORT = 12525;
    private static final int POP3_TEST_PORT = 11110;

    private static UserDao userDao;
    private static EmailDao emailDao;
    private static SmtpServer smtpServer;
    private static Pop3Server pop3Server;

    @BeforeAll
    public static void setUp() throws IOException {
        // Khởi tạo Database (MySQL nếu có, hoặc tự động Fallback SQLite)
        DatabaseManager.initialize();
        userDao = new UserDao();
        emailDao = new EmailDao();

        // Khởi động SMTP Server cổng test
        smtpServer = new SmtpServer(SMTP_TEST_PORT, emailDao, new GmailForwarder(null), null);
        smtpServer.start();

        // Khởi động POP3 Server cổng test
        pop3Server = new Pop3Server(POP3_TEST_PORT, userDao, emailDao, null);
        pop3Server.start();
    }

    @AfterAll
    public static void tearDown() {
        if (smtpServer != null) smtpServer.stop();
        if (pop3Server != null) pop3Server.stop();
    }

    @Test
    @Order(1)
    public void testDatabaseConnection() throws Exception {
        try (Connection conn = DatabaseManager.getConnection()) {
            assertNotNull(conn, "Kết nối CSDL không được null");
            assertTrue(conn.isValid(2), "Kết nối CSDL phải hợp lệ");
            assertNotNull(DatabaseManager.getDatabaseDescription());
            System.out.println("[TEST] CSDL đang dùng: " + DatabaseManager.getDatabaseDescription());
        }
    }

    @Test
    @Order(2)
    public void testUserRegistrationAndAuth() {
        String uAlice = "test_alice_" + System.currentTimeMillis();
        String uBob = "test_bob_" + System.currentTimeMillis();

        // Đăng ký
        assertTrue(userDao.register(uAlice, "password123", uAlice + "@localhost"));
        assertTrue(userDao.register(uBob, "secret456", uBob + "@localhost"));

        // Kiểm tra tồn tại
        assertTrue(userDao.existsByUsername(uAlice));
        assertTrue(userDao.existsByUsername(uBob));
        assertFalse(userDao.existsByUsername("non_existent_user_xyz"));

        // Xác thực đúng
        User authAlice = userDao.authenticate(uAlice, "password123");
        assertNotNull(authAlice);
        assertEquals(uAlice, authAlice.getUsername());

        // Xác thực sai mật khẩu
        assertNull(userDao.authenticate(uAlice, "wrong_pass"));
    }

    @Test
    @Order(3)
    public void testSmtpSendWithAttachment() {
        String sender = "tester_sender@localhost";
        String recipient = "tester_receiver@localhost";

        EmailMessage email = new EmailMessage();
        email.setSender(sender);
        email.setRecipient(recipient);
        email.setSubject("Thư kiểm thử SMTP Socket có đính kèm");
        email.setBody("Xin chào, đây là nội dung kiểm thử gửi qua cổng TCP 12525.");

        // Thêm tệp đính kèm
        byte[] fileBytes = "Day la du lieu tep dinh kem mau!".getBytes(StandardCharsets.UTF_8);
        email.addAttachment(new Attachment("sample_doc.txt", fileBytes.length, fileBytes));

        // Gửi qua SMTP Socket
        SmtpClient.SmtpResult sendResult = SmtpClient.sendEmail("127.0.0.1", SMTP_TEST_PORT, email);
        assertTrue(sendResult.success, "Gửi SMTP phải thành công: " + sendResult.message);

        // Kiểm tra CSDL Server đã lưu email và file đính kèm
        List<EmailMessage> inbox = emailDao.getInbox("tester_receiver");
        assertFalse(inbox.isEmpty(), "Hộp thư đến của người nhận không được rỗng");

        EmailMessage received = inbox.get(0);
        assertEquals("Thư kiểm thử SMTP Socket có đính kèm", received.getSubject());
        assertTrue(received.hasAttachments(), "Thư phải có tệp đính kèm");
        assertEquals("sample_doc.txt", received.getAttachments().get(0).getFilename());
        assertArrayEquals(fileBytes, received.getAttachments().get(0).getData());
    }

    @Test
    @Order(4)
    public void testPop3FetchMailboxAndRetrieval() {
        String testUser = "pop3_tester_" + System.currentTimeMillis();
        String pass = "pop3pass";
        userDao.register(testUser, pass, testUser + "@localhost");

        // Gửi 1 email tới user này qua SMTP
        EmailMessage email = new EmailMessage();
        email.setSender("boss@localhost");
        email.setRecipient(testUser);
        email.setSubject("Hop khan cap");
        email.setBody("Noi dung cuoc hop luc 9h sang mai.");
        byte[] pdfFake = "%PDF-1.4 Fake PDF Content".getBytes(StandardCharsets.UTF_8);
        email.addAttachment(new Attachment("agenda.pdf", pdfFake.length, pdfFake));

        SmtpClient.SmtpResult res = SmtpClient.sendEmail("127.0.0.1", SMTP_TEST_PORT, email);
        assertTrue(res.success);

        // Client dùng POP3 để tải thư về
        Pop3Client.Pop3Result popRes = Pop3Client.fetchEmails("127.0.0.1", POP3_TEST_PORT, testUser, pass);
        assertTrue(popRes.success, "Tải POP3 phải thành công: " + popRes.message);
        assertEquals(1, popRes.emails.size());

        EmailMessage fetched = popRes.emails.get(0);
        assertEquals("Hop khan cap", fetched.getSubject());
        assertTrue(fetched.getBody().contains("Noi dung cuoc hop"));
        assertTrue(fetched.hasAttachments());
        assertEquals("agenda.pdf", fetched.getAttachments().get(0).getFilename());
    }

    @Test
    @Order(5)
    public void testPop3AuthenticationFailure() {
        Pop3Client.Pop3Result popRes = Pop3Client.fetchEmails("127.0.0.1", POP3_TEST_PORT, "test_alice", "sai_mat_khau_ne");
        assertFalse(popRes.success, "Đăng nhập sai mật khẩu qua POP3 phải trả về lỗi");
        assertTrue(popRes.message.contains("Invalid username or password"));
    }

    @Test
    @Order(6)
    public void testPop3DeleteEmail() {
        String delUser = "user_delete_test_" + System.currentTimeMillis();
        userDao.register(delUser, "123456", delUser + "@localhost");

        // Gửi 1 thư
        EmailMessage em = new EmailMessage("sender@localhost", delUser, "Thư sắp bị xóa", "Tạm biệt!");
        SmtpClient.sendEmail("127.0.0.1", SMTP_TEST_PORT, em);

        // Kiểm tra có 1 thư
        Pop3Client.Pop3Result r1 = Pop3Client.fetchEmails("127.0.0.1", POP3_TEST_PORT, delUser, "123456");
        assertEquals(1, r1.emails.size());

        // Xóa thư số 1
        boolean deleted = Pop3Client.deleteEmail("127.0.0.1", POP3_TEST_PORT, delUser, "123456", 1);
        assertTrue(deleted, "Xóa thư qua POP3 DELE phải thành công");

        // Tải lại kiểm tra hộp thư rỗng
        Pop3Client.Pop3Result r2 = Pop3Client.fetchEmails("127.0.0.1", POP3_TEST_PORT, delUser, "123456");
        assertEquals(0, r2.emails.size(), "Hộp thư phải rỗng sau khi xóa");
    }
}
