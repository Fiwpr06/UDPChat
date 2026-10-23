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
 * Cầu nối giữa hệ thống UDP Mail và Gmail thật thông qua SMTP (gửi) và IMAP (nhận)
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
        this.logger = logger;
        this.smtpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Gmail-SMTP-Sender");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(attachmentsDir);
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // 1. GỬI THƯ QUA GMAIL SMTP (Bất đồng bộ)
    // =========================================================================

    /**
     * Gửi Email.java ra Gmail thật qua SMTP. Chạy bất đồng bộ để không block luồng UDP.
     *
     * @param email      Thư cần gửi
     * @param userEmails Bảng ánh xạ username nội bộ -> địa chỉ Gmail thực tế
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
                    logger.accept("[Gmail SMTP] Bỏ qua: Không tìm thấy địa chỉ email thật cho người nhận của thư '" + email.getSubject() + "'");
                    return false;
                }

                Transport.send(msg);

                String msgId = msg.getMessageID();
                if (msgId != null) {
                    email.setGmailMessageId(msgId);
                }
                logger.accept("[Gmail SMTP] ✓ Đã gửi thành công thư '" + email.getSubject() + "' ra Gmail thực tế.");
                return true;
            } catch (Exception e) {
                logger.accept("[Gmail SMTP] ✗ Lỗi gửi thư qua Gmail: " + e.getMessage());
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
                return new PasswordAuthentication(config.getGmailAddress(), config.getAppPassword());
            }
        });
    }

    private MimeMessage toMimeMessage(Session session, Email email, Map<String, String> userEmails) throws Exception {
        MimeMessage msg = new MimeMessage(session);

        // From
        String senderName = email.getSender() != null ? email.getSender() : "UDP Mail User";
        msg.setFrom(new InternetAddress(config.getGmailAddress(), senderName, "UTF-8"));

        // To
        boolean hasRecipient = false;
        if (email.getRecipients() != null) {
            for (String r : email.getRecipients()) {
                String targetEmail = resolveEmail(r, userEmails);
                if (targetEmail != null) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(targetEmail));
                    hasRecipient = true;
                }
            }
        }

        // Cc
        if (email.getCc() != null) {
            for (String c : email.getCc()) {
                String targetEmail = resolveEmail(c, userEmails);
                if (targetEmail != null) {
                    msg.addRecipient(Message.RecipientType.CC, new InternetAddress(targetEmail));
                    hasRecipient = true;
                }
            }
        }

        // Bcc
        if (email.getBcc() != null) {
            for (String b : email.getBcc()) {
                String targetEmail = resolveEmail(b, userEmails);
                if (targetEmail != null) {
                    msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(targetEmail));
                    hasRecipient = true;
                }
            }
        }

        if (!hasRecipient) {
            return null; // Không có email thật nào
        }

        msg.setSubject(email.getSubject(), "UTF-8");
        msg.setSentDate(new Date());

        // Threading headers
        if (email.getGmailMessageId() != null && !email.getGmailMessageId().isEmpty()) {
            msg.setHeader("In-Reply-To", email.getGmailMessageId());
            msg.setHeader("References", email.getGmailMessageId());
        }

        // Body & Attachments
        if (email.hasAttachments()) {
            Multipart multipart = new MimeMultipart();

            // Text part
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(email.getBody() != null ? email.getBody() : "", "UTF-8");
            multipart.addBodyPart(textPart);

            // Attachment parts
            for (Attachment att : email.getAttachments()) {
                File attFile = attachmentsDir.resolve(att.getId()).toFile();
                if (!attFile.exists()) {
                    attFile = attachmentsDir.resolve(att.getFilename()).toFile();
                }
                if (attFile.exists()) {
                    MimeBodyPart attachPart = new MimeBodyPart();
                    FileDataSource source = new FileDataSource(attFile);
                    attachPart.setDataHandler(new DataHandler(source));
                    attachPart.setFileName(MimeUtility.encodeText(att.getFilename(), "UTF-8", null));
                    multipart.addBodyPart(attachPart);
                }
            }
            msg.setContent(multipart);
        } else {
            msg.setText(email.getBody() != null ? email.getBody() : "", "UTF-8");
        }

        msg.saveChanges();
        return msg;
    }

    private String resolveEmail(String recipient, Map<String, String> userEmails) {
        if (recipient == null || recipient.trim().isEmpty()) return null;
        String clean = recipient.trim();
        if (clean.contains("@")) {
            return clean;
        }
        if (userEmails != null && userEmails.containsKey(clean)) {
            String mapped = userEmails.get(clean);
            if (mapped != null && mapped.contains("@")) {
                return mapped.trim();
            }
        }
        return null;
    }

    // =========================================================================
    // 2. NHẬN THƯ TỪ GMAIL IMAP (Đồng bộ định kỳ)
    // =========================================================================

    /**
     * Bắt đầu tiến trình định kỳ poll thư mới từ Gmail qua IMAP
     */
    public synchronized void startImapPolling(IncomingMailHandler handler) {
        this.incomingMailHandler = handler;
        if (imapRunning) {
            return;
        }
        if (!config.isEnabled() || !config.isConfigured()) {
            return;
        }

        imapScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Gmail-IMAP-Poller");
            t.setDaemon(true);
            return t;
        });

        int interval = config.getImapPollIntervalSeconds();
        imapRunning = true;
        imapScheduler.scheduleWithFixedDelay(this::pollImap, 3, interval, TimeUnit.SECONDS);
        logger.accept("[Gmail IMAP] Đã khởi động dịch vụ đồng bộ Gmail (chu kỳ " + interval + "s)");
    }

    public synchronized void stopImapPolling() {
        imapRunning = false;
        if (imapScheduler != null) {
            imapScheduler.shutdownNow();
            imapScheduler = null;
        }
        logger.accept("[Gmail IMAP] Đã dừng dịch vụ đồng bộ Gmail.");
    }

    private void pollImap() {
        if (!config.isEnabled() || !config.isConfigured()) return;

        Store store = null;
        Folder inbox = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", config.getImapHost());
            props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.protocols", "TLSv1.2");
            props.put("mail.imaps.connectiontimeout", "10000");
            props.put("mail.imaps.timeout", "15000");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(config.getImapHost(), config.getGmailAddress(), config.getAppPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            // Tìm kiếm các thư chưa đọc (UNSEEN)
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            if (messages != null && messages.length > 0) {
                logger.accept("[Gmail IMAP] Phát hiện " + messages.length + " thư mới trên Gmail!");
                for (Message msg : messages) {
                    try {
                        Email email = fromMimeMessage((MimeMessage) msg);
                        if (email != null && incomingMailHandler != null) {
                            incomingMailHandler.onNewMail(email);
                            // Đánh dấu đã đọc trên Gmail để không bị kéo lại lần sau
                            msg.setFlag(Flags.Flag.SEEN, true);
                        }
                    } catch (Exception e) {
                        logger.accept("[Gmail IMAP] Lỗi xử lý thư từ Gmail: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.accept("[Gmail IMAP] Lỗi kết nối IMAP: " + e.getMessage());
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

        // Sender
        Address[] froms = msg.getFrom();
        if (froms != null && froms.length > 0) {
            email.setSender(extractAddress(froms[0]));
        } else {
            email.setSender("unknown@gmail.com");
        }

        // Recipients (TO)
        List<String> recipients = extractAddressList(msg.getRecipients(Message.RecipientType.TO));
        email.setRecipients(recipients);

        // CC
        List<String> cc = extractAddressList(msg.getRecipients(Message.RecipientType.CC));
        email.setCc(cc);

        // Sent date
        if (msg.getSentDate() != null) {
            LocalDateTime ldt = msg.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            email.setSentAt(ldt.format(formatter));
            email.setTimestamp(msg.getSentDate().getTime());
        } else {
            email.setSentAt(LocalDateTime.now().format(formatter));
        }

        // Message-ID
        String msgId = msg.getMessageID();
        if (msgId != null) {
            email.setGmailMessageId(msgId);
        }

        // Body and Attachments
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
            // Nếu chưa có plain text thì fallback sang trích xuất html text cơ bản
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
            // Tệp đính kèm
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
