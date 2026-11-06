package com.udpchat.client.service;

import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;

import java.util.Properties;

/**
 * Tiện ích gửi thư điện tử trực tiếp qua máy chủ SMTP của Gmail (smtp.gmail.com:587 TLS)
 * sử dụng Mật khẩu ứng dụng (App Password 16 ký tự).
 */
public class GmailDirectClient {

    public static class SendResult {
        public final boolean success;
        public final String message;

        public SendResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static SendResult sendViaGmail(String gmailAccount, String appPassword, EmailMessage email) {
        if (gmailAccount == null || gmailAccount.trim().isEmpty()) {
            return new SendResult(false, "Vui lòng nhập địa chỉ Gmail gửi.");
        }
        if (appPassword == null || appPassword.trim().isEmpty()) {
            return new SendResult(false, "Vui lòng nhập Mật khẩu ứng dụng (App Password 16 ký tự).");
        }
        if (email == null || email.getRecipient() == null || email.getRecipient().trim().isEmpty()) {
            return new SendResult(false, "Vui lòng nhập người nhận.");
        }

        String cleanPass = appPassword.trim().replaceAll("\\s+", "");

        try {
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
                    return new PasswordAuthentication(gmailAccount.trim(), cleanPass);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(gmailAccount.trim(), "UDPChat Mail Client", "UTF-8"));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(email.getRecipient().trim()));
            msg.setSubject(email.getSubject(), "UTF-8");

            String footer = "\n\n---\nThư được gửi trực tiếp từ UDPChat Mail Client.";

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
            return new SendResult(true, "Đã gửi thư thành công ra Gmail tới " + email.getRecipient());
        } catch (Exception e) {
            return new SendResult(false, "Lỗi gửi Gmail: " + e.getMessage());
        }
    }
}
