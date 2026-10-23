package com.udpchat.server.service;

import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Dịch vụ gửi thư điện tử ra Internet (Gmail SMTP) sử dụng chuẩn xác thực XOAUTH2 của từng Client.
 * Nếu người gửi chưa liên kết tài khoản Google OAuth 2.0, thư sẽ được phân phối và lưu trữ nội bộ qua UDP/MySQL.
 */
public class SmtpDeliveryService {
    private final OAuthService oAuthService;
    private final Consumer<String> logger;
    private final ExecutorService smtpExecutor;
    private final Path attachmentsDir;

    public SmtpDeliveryService(OAuthService oAuthService, Consumer<String> logger) {
        this.oAuthService = oAuthService;
        this.logger = logger != null ? logger : System.out::println;
        this.attachmentsDir = Paths.get("server-data", "attachments");
        this.smtpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Server-SMTP-Delivery");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(attachmentsDir);
        } catch (IOException ignored) {}
    }

    public OAuthService getOAuthService() {
        return oAuthService;
    }

    /**
     * Gửi thư qua Gmail SMTP bằng cơ chế XOAUTH2 sử dụng access token của chính người gửi.
     */
    public CompletableFuture<Boolean> sendViaSmtp(Email email, Map<String, String> userEmails) {
        if (email == null) return CompletableFuture.completedFuture(false);

        String sender = email.getSender();
        if (oAuthService != null && sender != null && oAuthService.isLinked(sender)) {
            String accessToken = oAuthService.getValidAccessToken(sender);
            String senderGmail = oAuthService.getLinkedEmail(sender);

            if (accessToken != null && senderGmail != null) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        logger.accept("[SMTP XOAUTH2] Đang chuyển tiếp thư '" + email.getSubject() + "' từ tài khoản " + senderGmail + "...");
                        Session session = createOAuthSmtpSession(senderGmail, accessToken);
                        MimeMessage msg = toMimeMessage(session, email, senderGmail, userEmails);
                        if (msg == null) {
                            logger.accept("[SMTP XOAUTH2] Bỏ qua: Không có người nhận email thực tế hợp lệ.");
                            return false;
                        }

                        Transport.send(msg);
                        if (msg.getMessageID() != null) {
                            email.setGmailMessageId(msg.getMessageID());
                        }
                        logger.accept("[SMTP XOAUTH2] ✓ Đã gửi thành công thư '" + email.getSubject() + "' ra Gmail thực tế.");
                        return true;
                    } catch (Exception e) {
                        logger.accept("[SMTP XOAUTH2] ✗ Lỗi gửi thư qua XOAUTH2: " + e.getMessage());
                        return false;
                    }
                }, smtpExecutor);
            }
        }

        logger.accept("[SMTP Delivery] Người gửi '" + sender + "' chưa liên kết Google OAuth; thư được lưu và phân phối nội bộ qua UDP/MySQL.");
        return CompletableFuture.completedFuture(false);
    }

    private Session createOAuthSmtpSession(String senderGmail, String accessToken) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
        props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderGmail, accessToken);
            }
        });
    }

    private MimeMessage toMimeMessage(Session session, Email email, String actualFromAddress, Map<String, String> userEmails) throws Exception {
        MimeMessage msg = new MimeMessage(session);

        String senderName = email.getSender() != null ? email.getSender() : "UDPChat User";
        String senderEmail = email.getSenderEmail() != null ? email.getSenderEmail() : actualFromAddress;
        String fromDisplay = senderName + (senderEmail != null ? " (" + senderEmail + ")" : "") + " (qua UDP Mail Chat)";
        msg.setFrom(new InternetAddress(actualFromAddress, fromDisplay, "UTF-8"));

        if (senderEmail != null && senderEmail.contains("@")) {
            msg.setReplyTo(new Address[]{ new InternetAddress(senderEmail, senderName, "UTF-8") });
        } else {
            msg.setReplyTo(new Address[]{ new InternetAddress(actualFromAddress, senderName, "UTF-8") });
        }

        // Recipients (TO)
        boolean hasRecipient = false;
        if (email.getRecipients() != null) {
            for (String r : email.getRecipients()) {
                if (r == null || r.trim().isEmpty() || r.toLowerCase().contains("@udpmail")) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    String realEm = userEmails.get(addr);
                    if (realEm != null && realEm.contains("@") && !realEm.toLowerCase().contains("@udpmail")) {
                        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(realEm));
                        hasRecipient = true;
                    }
                }
            }
        }

        // CC
        if (email.getCc() != null) {
            for (String r : email.getCc()) {
                if (r == null || r.trim().isEmpty() || r.toLowerCase().contains("@udpmail")) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.CC, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    String realEm = userEmails.get(addr);
                    if (realEm != null && realEm.contains("@") && !realEm.toLowerCase().contains("@udpmail")) {
                        msg.addRecipient(Message.RecipientType.CC, new InternetAddress(realEm));
                        hasRecipient = true;
                    }
                }
            }
        }

        // BCC
        if (email.getBcc() != null) {
            for (String r : email.getBcc()) {
                if (r == null || r.trim().isEmpty() || r.toLowerCase().contains("@udpmail")) continue;
                String addr = r.trim();
                if (addr.contains("@")) {
                    msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(addr));
                    hasRecipient = true;
                } else if (userEmails != null && userEmails.containsKey(addr)) {
                    String realEm = userEmails.get(addr);
                    if (realEm != null && realEm.contains("@") && !realEm.toLowerCase().contains("@udpmail")) {
                        msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(realEm));
                        hasRecipient = true;
                    }
                }
            }
        }

        if (!hasRecipient) {
            return null;
        }

        msg.setSubject(email.getSubject() != null ? email.getSubject() : "(Không có tiêu đề)", "UTF-8");
        msg.setSentDate(new Date());

        String footer = "\n\n---\nThư được gửi thông qua Hệ thống UDP Mail Chat.";

        if (email.getAttachments() != null && !email.getAttachments().isEmpty()) {
            Multipart multipart = new MimeMultipart();

            MimeBodyPart textPart = new MimeBodyPart();
            String fullBody = (email.getBody() != null ? email.getBody() : "") + footer;
            textPart.setText(fullBody, "UTF-8");
            multipart.addBodyPart(textPart);

            for (Attachment att : email.getAttachments()) {
                Path filePath = attachmentsDir.resolve(att.getFilename());
                File file = filePath.toFile();
                if (file.exists()) {
                    MimeBodyPart attachPart = new MimeBodyPart();
                    attachPart.setDataHandler(new DataHandler(new FileDataSource(file)));
                    attachPart.setFileName(att.getFilename());
                    multipart.addBodyPart(attachPart);
                }
            }
            msg.setContent(multipart);
        } else {
            String fullBody = (email.getBody() != null ? email.getBody() : "") + footer;
            msg.setText(fullBody, "UTF-8");
        }

        return msg;
    }

    public void shutdown() {
        if (smtpExecutor != null && !smtpExecutor.isShutdown()) {
            smtpExecutor.shutdown();
        }
    }
}
