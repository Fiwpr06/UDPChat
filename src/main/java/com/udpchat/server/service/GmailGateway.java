package com.udpchat.server.service;

import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.FlagTerm;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Cầu nối trung gian giữa hệ thống UDP Mail Server và Gmail thật thông qua SMTP (gửi) và IMAP (nhận)
 */
public class GmailGateway {
    private final GmailConfig config;
    private final Path attachmentsDir;
    private final Consumer<String> logger;
    private final ExecutorService smtpExecutor;
    private ScheduledExecutorService imapScheduler;
    private volatile boolean imapRunning = false;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FunctionalInterface
    public interface IncomingMailHandler {
        void onNewMail(Email email);
    }
    private IncomingMailHandler incomingMailHandler;

    public GmailGateway(GmailConfig config, Consumer<String> logger) {
        this.config = config;
        this.attachmentsDir = Paths.get("server-data", "attachments");
        this.logger = logger != null ? logger : System.out::println;
        this.smtpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Server-Gmail-SMTP");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(attachmentsDir);
        } catch (IOException ignored) {}
    }

    public GmailConfig getConfig() {
        return config;
    }

    // =========================================================================
    // 0. XÁC THỰC KẾT NỐI (TEST CONNECTION)
    // =========================================================================

    public static class ConnectionTestResult {
        private final boolean success;
        private final String message;

        public ConnectionTestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    /**
     * Kiểm tra thực tế thông tin tài khoản và mật khẩu ứng dụng Gmail qua SMTP
     */
    public CompletableFuture<ConnectionTestResult> testConnection(String address, String password) {
        return CompletableFuture.supplyAsync(() -> {
            if (address == null || address.trim().isEmpty()) {
                return new ConnectionTestResult(false, "Vui lòng nhập địa chỉ Gmail.");
            }
            if (password == null || password.trim().isEmpty()) {
                return new ConnectionTestResult(false, "Vui lòng nhập Mật khẩu ứng dụng Google (16 ký tự).");
            }

            String cleanPass = password.replaceAll("\\s+", "").trim();
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");
            props.put("mail.smtp.ssl.trust", config.getSmtpHost());
            props.put("mail.smtp.connectiontimeout", "8000");
            props.put("mail.smtp.timeout", "8000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(address.trim(), cleanPass);
                }
            });

            try {
                Transport transport = session.getTransport("smtp");
                transport.connect(config.getSmtpHost(), config.getSmtpPort(), address.trim(), cleanPass);
                transport.close();
                return new ConnectionTestResult(true, "Kết nối và xác thực thành công với Google SMTP!");
            } catch (AuthenticationFailedException e) {
                String hint = "Google từ chối mật khẩu. Lưu ý: Bạn PHẢI dùng 'Mật khẩu ứng dụng' (16 chữ cái tạo tại https://myaccount.google.com/apppasswords), KHÔNG dùng mật khẩu đăng nhập thông thường.";
                return new ConnectionTestResult(false, "Xác thực thất bại (535): " + hint);
            } catch (Exception e) {
                return new ConnectionTestResult(false, "Lỗi kết nối tới smtp.gmail.com: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    // 1. GỬI THƯ QUA GMAIL SMTP (Bất đồng bộ)
    // =========================================================================

    /**
     * Gửi Email ra Gmail thật qua SMTP. Chạy bất đồng bộ để không block luồng UDP Server.
     */
    public CompletableFuture<Boolean> sendViaSmtp(Email email, Map<String, String> userEmails) {
        if (!config.isEnabled() || !config.isConfigured()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Session session = createSmtpSession();
                MimeMessage msg = toMimeMessage(session, email, userEmails);
                if (msg == null) {
                    logger.accept("[Gmail SMTP Gateway] Bỏ qua: Không có người nhận email thực tế hợp lệ.");
                    return false;
                }

                logger.accept("[Gmail SMTP Gateway] Đang chuyển tiếp thư '" + email.getSubject() + "' tới Gmail...");
                Transport.send(msg);

                String msgId = msg.getMessageID();
                if (msgId != null) {
                    email.setGmailMessageId(msgId);
                }
                logger.accept("[Gmail SMTP Gateway] ✓ Đã gửi thành công thư '" + email.getSubject() + "' ra Gmail thực tế.");
                return true;
            } catch (AuthenticationFailedException e) {
                logger.accept("[Gmail SMTP Gateway] ✗ Lỗi xác thực Google (535 Bad Credentials). Vui lòng kiểm tra lại App Password trên Server.");
                return false;
            } catch (Exception e) {
                logger.accept("[Gmail SMTP Gateway] ✗ Lỗi gửi thư qua Gmail: " + e.getMessage());
                return false;
            }
        }, smtpExecutor);
    }

    private Session createSmtpSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.ssl.trust", config.getSmtpHost());
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getGmailAddress(), config.getCleanAppPassword());
            }
        });
    }

    private MimeMessage toMimeMessage(Session session, Email email, Map<String, String> userEmails) throws Exception {
        MimeMessage msg = new MimeMessage(session);

        // Sender: Tên người gửi nội bộ kèm đại diện Server Gateway
        String senderName = email.getSender() != null ? email.getSender() : "UDPChat User";
        String fromDisplay = senderName + " (qua UDPChat Server)";
        msg.setFrom(new InternetAddress(config.getGmailAddress(), fromDisplay, "UTF-8"));

        // Reply-To: Cho phép người nhận bấm Reply gửi lại cho sender
        msg.setReplyTo(new Address[]{ new InternetAddress(config.getGmailAddress(), senderName, "UTF-8") });

        // Recipients (TO)
        boolean hasRecipient = false;
        if (email.getRecipients() != null) {
            for (String r : email.getRecipients()) {
                if (r == null || r.trim().isEmpty()) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(userEmails.get(addr)));
                    hasRecipient = true;
                }
            }
        }

        // CC
        if (email.getCc() != null) {
            for (String r : email.getCc()) {
                if (r == null || r.trim().isEmpty()) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.CC, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    msg.addRecipient(Message.RecipientType.CC, new InternetAddress(userEmails.get(addr)));
                    hasRecipient = true;
                }
            }
        }

        // BCC
        if (email.getBcc() != null) {
            for (String r : email.getBcc()) {
                if (r == null || r.trim().isEmpty()) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(userEmails.get(addr)));
                    hasRecipient = true;
                }
            }
        }

        if (!hasRecipient) {
            return null;
        }

        // Subject
        msg.setSubject(email.getSubject() != null ? email.getSubject() : "(Không có tiêu đề)", "UTF-8");
        msg.setSentDate(new Date());

        // Body và Attachments
        if (email.getAttachments() != null && !email.getAttachments().isEmpty()) {
            Multipart multipart = new MimeMultipart();

            // Text part
            MimeBodyPart textPart = new MimeBodyPart();
            String fullBody = (email.getBody() != null ? email.getBody() : "")
                    + "\n\n__________________________________\n"
                    + "[Thư gửi từ hệ thống UDP Mail Chat - Người gửi: " + senderName + "]";
            textPart.setText(fullBody, "UTF-8");
            multipart.addBodyPart(textPart);

            // Attachment parts
            for (Attachment att : email.getAttachments()) {
                File file = attachmentsDir.resolve(att.getFilename()).toFile();
                if (!file.exists()) {
                    file = attachmentsDir.resolve(att.getId() + "_" + att.getFilename()).toFile();
                }
                if (file.exists()) {
                    MimeBodyPart attachPart = new MimeBodyPart();
                    attachPart.setDataHandler(new DataHandler(new FileDataSource(file)));
                    attachPart.setFileName(MimeUtility.encodeText(att.getFilename()));
                    multipart.addBodyPart(attachPart);
                } else {
                    logger.accept("[Gmail SMTP Gateway] Không tìm thấy file đính kèm trên server: " + att.getFilename());
                }
            }
            msg.setContent(multipart);
        } else {
            String fullBody = (email.getBody() != null ? email.getBody() : "")
                    + "\n\n__________________________________\n"
                    + "[Thư gửi từ hệ thống UDP Mail Chat - Người gửi: " + senderName + "]";
            msg.setText(fullBody, "UTF-8");
        }

        return msg;
    }

    // =========================================================================
    // 2. KÉO THƯ QUA GMAIL IMAP (Poller chu kỳ)
    // =========================================================================

    public synchronized void startImapPolling(IncomingMailHandler handler) {
        if (!config.isEnabled() || !config.isConfigured()) return;
        this.incomingMailHandler = handler;

        if (imapScheduler == null || imapScheduler.isShutdown()) {
            imapScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Server-Gmail-IMAP-Poller");
                t.setDaemon(true);
                return t;
            });
        }

        if (!imapRunning) {
            imapRunning = true;
            int interval = Math.max(10, config.getImapPollIntervalSeconds());
            logger.accept("[Gmail IMAP Gateway] Bắt đầu dịch vụ kiểm tra thư Gmail định kỳ mỗi " + interval + "s.");
            imapScheduler.scheduleWithFixedDelay(this::pollImapSafely, 2, interval, TimeUnit.SECONDS);
        }
    }

    public synchronized void stopImapPolling() {
        imapRunning = false;
        if (imapScheduler != null && !imapScheduler.isShutdown()) {
            imapScheduler.shutdownNow();
            logger.accept("[Gmail IMAP Gateway] Đã dừng dịch vụ kiểm tra thư định kỳ.");
        }
    }

    public void syncNow(IncomingMailHandler handler) {
        this.incomingMailHandler = handler;
        CompletableFuture.runAsync(this::pollImapSafely);
    }

    private void pollImapSafely() {
        try {
            pollImapOnce();
        } catch (Exception e) {
            logger.accept("[Gmail IMAP Gateway] Lỗi trong phiên quét hòm thư: " + e.getMessage());
        }
    }

    private void pollImapOnce() {
        if (!config.isEnabled() || !config.isConfigured()) return;

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", config.getImapHost());
        props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.protocols", "TLSv1.2");
        props.put("mail.imaps.timeout", "15000");

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(config.getImapHost(), config.getGmailAddress(), config.getCleanAppPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            if (messages.length > 0) {
                logger.accept("[Gmail IMAP Gateway] Phát hiện " + messages.length + " thư mới trên Gmail!");
                for (Message m : messages) {
                    if (m instanceof MimeMessage mimeMsg) {
                        Email email = fromMimeMessage(mimeMsg);
                        m.setFlag(Flags.Flag.SEEN, true);

                        if (incomingMailHandler != null && email != null) {
                            incomingMailHandler.onNewMail(email);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.accept("[Gmail IMAP Gateway] Lỗi kết nối IMAP: " + e.getMessage());
        } finally {
            try {
                if (inbox != null && inbox.isOpen()) inbox.close(false);
                if (store != null && store.isConnected()) store.close();
            } catch (Exception ignored) {}
        }
    }

    private Email fromMimeMessage(MimeMessage msg) throws Exception {
        Email email = new Email();
        email.setSubject(msg.getSubject() != null ? msg.getSubject() : "(Không có tiêu đề)");

        Address[] froms = msg.getFrom();
        if (froms != null && froms.length > 0) {
            email.setSender(extractAddress(froms[0]));
        } else {
            email.setSender("unknown@gmail.com");
        }

        List<String> recipients = extractAddressList(msg.getRecipients(Message.RecipientType.TO));
        email.setRecipients(recipients);

        List<String> cc = extractAddressList(msg.getRecipients(Message.RecipientType.CC));
        email.setCc(cc);

        if (msg.getSentDate() != null) {
            LocalDateTime ldt = msg.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            email.setSentAt(ldt.format(formatter));
            email.setTimestamp(msg.getSentDate().getTime());
        } else {
            email.setSentAt(LocalDateTime.now().format(formatter));
        }

        String msgId = msg.getMessageID();
        if (msgId != null) {
            email.setGmailMessageId(msgId);
        }

        List<Attachment> attachments = new ArrayList<>();
        StringBuilder bodyBuilder = new StringBuilder();
        parseContent(msg, bodyBuilder, attachments);

        email.setBody(bodyBuilder.toString().trim());
        email.setAttachments(attachments);
        email.setFolder(MailFolder.INBOX);
        email.setRead(false);

        return email;
    }

    private void parseContent(Part part, StringBuilder bodyBuilder, List<Attachment> attachments) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            if (content != null) {
                bodyBuilder.append(content.toString()).append("\n");
            }
        } else if (part.isMimeType("text/html")) {
            if (bodyBuilder.length() == 0) {
                Object content = part.getContent();
                if (content != null) {
                    String clean = content.toString().replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ");
                    bodyBuilder.append(clean).append("\n");
                }
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            int count = mp.getCount();
            for (int i = 0; i < count; i++) {
                parseContent(mp.getBodyPart(i), bodyBuilder, attachments);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            String originalFileName = part.getFileName();
            if (originalFileName != null) {
                originalFileName = MimeUtility.decodeText(originalFileName);
            } else {
                originalFileName = "attachment_" + System.currentTimeMillis();
            }

            String attId = UUID.randomUUID().toString();
            File saveFile = attachmentsDir.resolve(originalFileName).toFile();
            try (InputStream is = part.getInputStream();
                 FileOutputStream fos = new FileOutputStream(saveFile)) {
                is.transferTo(fos);
            }

            long size = saveFile.length();
            String cType = Attachment.detectContentType(originalFileName);
            String uploadTime = LocalDateTime.now().format(formatter);

            Attachment att = new Attachment(attId, originalFileName, size, cType, "Gmail", uploadTime);
            attachments.add(att);
        }
    }

    private String extractAddress(Address addr) {
        if (addr instanceof InternetAddress) {
            return ((InternetAddress) addr).getAddress();
        }
        return addr != null ? addr.toString() : "";
    }

    private List<String> extractAddressList(Address[] addrs) {
        List<String> list = new ArrayList<>();
        if (addrs == null) return list;
        for (Address a : addrs) {
            String clean = extractAddress(a);
            if (!clean.isEmpty()) {
                list.add(clean);
            }
        }
        return list;
    }

    public void shutdown() {
        stopImapPolling();
        smtpExecutor.shutdown();
    }
}
