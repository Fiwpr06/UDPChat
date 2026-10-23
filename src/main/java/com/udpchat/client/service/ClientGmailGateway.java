package com.udpchat.client.service;

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
 * Xử lý kết nối trực tiếp từ Client tới Gmail qua SMTP (gửi) và IMAP (nhận)
 * HOÀN TOÀN KHÔNG QUA TRUNG GIAN SERVER
 */
public class ClientGmailGateway {
    private final ClientGmailConfig config;
    private final Path cacheDir;
    private final ExecutorService executor;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ClientGmailGateway(ClientGmailConfig config) {
        this.config = config;
        this.cacheDir = Paths.get("client-cache");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Client-Direct-Gmail");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // 1. GỬI THƯ TRỰC TIẾP QUA GMAIL SMTP (Client -> Google)
    // =========================================================================

    public static class SmtpSendResult {
        private final boolean success;
        private final String message;

        public SmtpSendResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    /**
     * Gửi email trực tiếp từ Client lên máy chủ Gmail bằng tài khoản cá nhân của Client
     */
    public CompletableFuture<SmtpSendResult> sendDirectViaSmtp(Email email, List<File> localAttachedFiles, Consumer<String> logger) {
        if (!config.isEnabled() || !config.isConfigured()) {
            return CompletableFuture.completedFuture(new SmtpSendResult(false, "Chưa bật hoặc chưa cấu hình Gmail"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (logger != null) logger.accept("[Gmail SMTP Trực tiếp] Đang kết nối tới " + config.getSmtpHost() + ":" + config.getSmtpPort() + "...");

                Session session = createSmtpSession();
                MimeMessage msg = new MimeMessage(session);

                // From: Luôn là tài khoản Gmail của chính Client này
                String senderDisplayName = email.getSender() != null ? email.getSender() : "Client User";
                msg.setFrom(new InternetAddress(config.getGmailAddress(), senderDisplayName, "UTF-8"));

                // To, CC, BCC: Lọc các địa chỉ có định dạng email thực tế
                boolean hasValidEmailRecipient = false;
                if (email.getRecipients() != null) {
                    for (String r : email.getRecipients()) {
                        String clean = r.trim();
                        if (clean.contains("@")) {
                            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(clean));
                            hasValidEmailRecipient = true;
                        }
                    }
                }
                if (email.getCc() != null) {
                    for (String c : email.getCc()) {
                        String clean = c.trim();
                        if (clean.contains("@")) {
                            msg.addRecipient(Message.RecipientType.CC, new InternetAddress(clean));
                            hasValidEmailRecipient = true;
                        }
                    }
                }
                if (email.getBcc() != null) {
                    for (String b : email.getBcc()) {
                        String clean = b.trim();
                        if (clean.contains("@")) {
                            msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(clean));
                            hasValidEmailRecipient = true;
                        }
                    }
                }

                if (!hasValidEmailRecipient) {
                    String err = "Không có người nhận nào là địa chỉ email hợp lệ (có chứa ký tự @)";
                    if (logger != null) logger.accept("[Gmail SMTP Trực tiếp] Bỏ qua: " + err);
                    return new SmtpSendResult(false, err);
                }

                msg.setSubject(email.getSubject(), "UTF-8");
                msg.setSentDate(new Date());

                // Content & Attachments
                if (localAttachedFiles != null && !localAttachedFiles.isEmpty()) {
                    Multipart multipart = new MimeMultipart();

                    // Text part
                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText(email.getBody() != null ? email.getBody() : "", "UTF-8");
                    multipart.addBodyPart(textPart);

                    // Attachment parts
                    for (File f : localAttachedFiles) {
                        if (f != null && f.exists()) {
                            MimeBodyPart attachPart = new MimeBodyPart();
                            FileDataSource source = new FileDataSource(f);
                            attachPart.setDataHandler(new DataHandler(source));
                            attachPart.setFileName(MimeUtility.encodeText(f.getName(), "UTF-8", null));
                            multipart.addBodyPart(attachPart);
                        }
                    }
                    msg.setContent(multipart);
                } else {
                    msg.setText(email.getBody() != null ? email.getBody() : "", "UTF-8");
                }

                msg.saveChanges();
                Transport.send(msg);

                String msgId = msg.getMessageID();
                if (msgId != null) {
                    email.setGmailMessageId(msgId);
                }

                if (logger != null) {
                    logger.accept("[Gmail SMTP Trực tiếp] ✓ Đã gửi trực tiếp tới Gmail thành công! (Tài khoản: " + config.getGmailAddress() + ")");
                }
                return new SmtpSendResult(true, "Đã gửi trực tiếp tới Gmail thành công!");
            } catch (AuthenticationFailedException e) {
                String err = "Google từ chối mật khẩu (Mã 535). Bạn cần tạo Mật khẩu ứng dụng (App Password) 16 ký tự tại myaccount.google.com/apppasswords thay vì dùng mật khẩu thông thường.";
                if (logger != null) logger.accept("[Gmail SMTP Trực tiếp] ✗ " + err);
                return new SmtpSendResult(false, err);
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (err.contains("535") || err.contains("Username and Password not accepted") || err.contains("BadCredentials")) {
                    err = "Google từ chối mật khẩu (Mã 535). Bạn cần tạo Mật khẩu ứng dụng (App Password) 16 ký tự tại myaccount.google.com/apppasswords thay vì dùng mật khẩu thông thường.";
                }
                if (logger != null) logger.accept("[Gmail SMTP Trực tiếp] ✗ Lỗi gửi SMTP: " + err);
                return new SmtpSendResult(false, err);
            }
        }, executor);
    }

    private Session createSmtpSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.ssl.trust", config.getSmtpHost());
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "12000");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getGmailAddress(), config.getCleanAppPassword());
            }
        });
    }

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
     * Xác thực và kiểm tra kết nối trực tiếp tới Google SMTP
     */
    public ConnectionTestResult testConnection(String address, String password) {
        if (address == null || address.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return new ConnectionTestResult(false, "Vui lòng nhập đầy đủ Địa chỉ Gmail và Mật khẩu ứng dụng.");
        }
        String cleanAddr = address.trim();
        String cleanPass = password.replaceAll("\\s+", "").trim();

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        props.put("mail.smtp.ssl.trust", config.getSmtpHost());
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "10000");

        Session session = Session.getInstance(props);
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(config.getSmtpHost(), config.getSmtpPort(), cleanAddr, cleanPass);
            return new ConnectionTestResult(true, "✓ Xác nhận thành công! Kết nối tới máy chủ Google SMTP hoạt động hoàn hảo.");
        } catch (AuthenticationFailedException e) {
            return new ConnectionTestResult(false, "✗ Lỗi xác thực (Mã 535): Google từ chối mật khẩu này!\n" +
                    "Nguyên nhân: Bạn đang nhập mật khẩu đăng nhập thông thường của tài khoản.\n" +
                    "Cách khắc phục: Bắt buộc phải tạo 'Mật khẩu ứng dụng (App Password)' 16 ký tự tại: myaccount.google.com/apppasswords");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("535") || msg.contains("Username and Password not accepted") || msg.contains("BadCredentials")) {
                return new ConnectionTestResult(false, "✗ Lỗi xác thực (Mã 535): Google từ chối mật khẩu này!\n" +
                        "Nguyên nhân: Bạn đang nhập mật khẩu đăng nhập thông thường của tài khoản.\n" +
                        "Cách khắc phục: Bắt buộc phải tạo 'Mật khẩu ứng dụng (App Password)' 16 ký tự tại: myaccount.google.com/apppasswords");
            }
            return new ConnectionTestResult(false, "✗ Không thể kết nối tới Google: " + msg);
        }
    }

    // =========================================================================
    // 2. NHẬN THƯ TRỰC TIẾP QUA GMAIL IMAP (Google -> Client)
    // =========================================================================

    /**
     * Kéo các thư chưa đọc từ hộp thư Gmail của chính Client về máy
     */
    public List<Email> fetchUnreadViaImap(Consumer<String> logger) {
        if (!config.isEnabled() || !config.isConfigured()) {
            return Collections.emptyList();
        }

        List<Email> result = new ArrayList<>();
        Store store = null;
        Folder inbox = null;

        try {
            if (logger != null) logger.accept("[Gmail IMAP Trực tiếp] Đang kết nối tới " + config.getImapHost() + "...");

            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", config.getImapHost());
            props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.protocols", "TLSv1.2");
            props.put("mail.imaps.connectiontimeout", "8000");
            props.put("mail.imaps.timeout", "12000");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(config.getImapHost(), config.getGmailAddress(), config.getCleanAppPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            if (messages != null && messages.length > 0) {
                if (logger != null) logger.accept("[Gmail IMAP Trực tiếp] Tìm thấy " + messages.length + " thư chưa đọc trên Gmail!");

                for (Message msg : messages) {
                    try {
                        Email email = fromMimeMessage((MimeMessage) msg);
                        if (email != null) {
                            result.add(email);
                            msg.setFlag(Flags.Flag.SEEN, true); // Đánh dấu đã đọc trên Gmail
                        }
                    } catch (Exception e) {
                        if (logger != null) logger.accept("[Gmail IMAP Trực tiếp] Lỗi phân tích thư: " + e.getMessage());
                    }
                }
            } else {
                if (logger != null) logger.accept("[Gmail IMAP Trực tiếp] Hộp thư Gmail không có thư mới chưa đọc.");
            }
        } catch (Exception e) {
            if (logger != null) logger.accept("[Gmail IMAP Trực tiếp] Lỗi IMAP: " + e.getMessage());
        } finally {
            try {
                if (inbox != null && inbox.isOpen()) inbox.close(false);
                if (store != null && store.isConnected()) store.close();
            } catch (Exception ignored) {}
        }

        return result;
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

        email.setRecipients(extractAddressList(msg.getRecipients(Message.RecipientType.TO)));
        email.setCc(extractAddressList(msg.getRecipients(Message.RecipientType.CC)));

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
            File saveFile = cacheDir.resolve(originalFileName).toFile();
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
        executor.shutdown();
    }
}
