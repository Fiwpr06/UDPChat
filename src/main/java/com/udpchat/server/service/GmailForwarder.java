package com.udpchat.server.service;

import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Chuyển tiếp thư ra Gmail thật thông qua máy chủ SMTP của Gmail (smtp.gmail.com:587 TLS)
 * sử dụng Mật khẩu ứng dụng (App Password 16 ký tự).
 */
public class GmailForwarder {
    private static String serverGmailAccount = "";
    private static String serverGmailAppPassword = "";
    private static boolean enabled = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GmailForwarder-Thread");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<String> logger;

    public GmailForwarder(Consumer<String> logger) {
        this.logger = logger != null ? logger : System.out::println;
    }

    public static void configure(String account, String appPassword, boolean isEnabled) {
        serverGmailAccount = account != null ? account.trim() : "";
        serverGmailAppPassword = appPassword != null ? appPassword.trim().replaceAll("\\s+", "") : "";
        enabled = isEnabled && !serverGmailAccount.isEmpty() && !serverGmailAppPassword.isEmpty();
    }

    public static boolean isConfigured() {
        return enabled;
    }

    public static String getServerGmailAccount() {
        return serverGmailAccount;
    }

    public void forwardAsync(EmailMessage email) {
        if (!enabled) {
            logger.accept("[GmailForwarder] Bỏ qua chuyển tiếp Gmail: Chưa cấu hình hoặc chưa bật Gmail App Password.");
            return;
        }

        executor.submit(() -> {
            try {
                logger.accept("[GmailForwarder] Đang chuyển tiếp email '" + email.getSubject() + "' tới " + email.getRecipient() + " qua smtp.gmail.com...");

                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", "smtp.gmail.com");
                props.put("mail.smtp.port", "587");
                props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
                props.put("mail.smtp.connectiontimeout", "10000");
                props.put("mail.smtp.timeout", "15000");

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(serverGmailAccount, serverGmailAppPassword);
                    }
                });

                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(serverGmailAccount, "UDPChat Mail Server (" + email.getSender() + ")", "UTF-8"));
                msg.setRecipient(Message.RecipientType.TO, new InternetAddress(email.getRecipient()));
                msg.setSubject("[Chuyển tiếp] " + email.getSubject(), "UTF-8");

                String footer = "\n\n---\nThư được chuyển tiếp tự động từ Localhost Mail Server của người gửi: " + email.getSender();

                if (email.hasAttachments()) {
                    Multipart multipart = new MimeMultipart();

                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText(email.getBody() + footer, "UTF-8");
                    multipart.addBodyPart(textPart);

                    for (Attachment att : email.getAttachments()) {
                        if (att.getData() != null && att.getData().length > 0) {
                            MimeBodyPart attachPart = new MimeBodyPart();
                            ByteArrayDataSource ds = new ByteArrayDataSource(att.getData(), "application/octet-stream");
                            attachPart.setDataHandler(new DataHandler(ds));
                            attachPart.setFileName(MimeUtility.encodeText(att.getFilename(), "UTF-8", null));
                            multipart.addBodyPart(attachPart);
                        }
                    }
                    msg.setContent(multipart);
                } else {
                    msg.setText(email.getBody() + footer, "UTF-8");
                }

                Transport.send(msg);
                logger.accept("[GmailForwarder] ✓ Đã chuyển tiếp thành công tới " + email.getRecipient());
            } catch (Exception e) {
                logger.accept("[GmailForwarder] ✗ Lỗi khi gửi tới Gmail: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
