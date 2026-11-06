package com.udpchat.common.util;

import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Tiện ích chuyển đổi đối tượng EmailMessage sang MIME text chuẩn RFC 822 / MIME Multipart
 * và ngược lại, sử dụng Jakarta Mail API.
 */
public class MimeUtil {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Chuyển đổi EmailMessage thành chuỗi MIME hoàn chỉnh để gửi qua lệnh DATA của SMTP hoặc trả về trong RETR của POP3
     */
    public static String toMimeString(EmailMessage email) {
        try {
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            msg.setFrom(new InternetAddress(email.getSender() != null ? email.getSender() : "unknown@localhost"));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(email.getRecipient() != null ? email.getRecipient() : "unknown@localhost"));
            msg.setSubject(email.getSubject(), "UTF-8");
            msg.setSentDate(new Date());

            if (email.hasAttachments()) {
                Multipart multipart = new MimeMultipart();

                // Phần nội dung văn bản
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(email.getBody(), "UTF-8");
                multipart.addBodyPart(textPart);

                // Các phần tệp đính kèm
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
                msg.setText(email.getBody(), "UTF-8");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            msg.writeTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[MimeUtil] Lỗi đóng gói MIME: " + e.getMessage());
            // Fallback văn bản thô đơn giản
            return "From: " + email.getSender() + "\r\n" +
                   "To: " + email.getRecipient() + "\r\n" +
                   "Subject: " + email.getSubject() + "\r\n\r\n" +
                   email.getBody();
        }
    }

    /**
     * Phân tích chuỗi MIME text nhận được từ lệnh DATA hoặc RETR thành đối tượng EmailMessage
     */
    public static EmailMessage parseMimeString(String mimeContent) {
        EmailMessage email = new EmailMessage();
        try {
            Session session = Session.getDefaultInstance(new Properties());
            ByteArrayInputStream bais = new ByteArrayInputStream(mimeContent.getBytes(StandardCharsets.UTF_8));
            MimeMessage msg = new MimeMessage(session, bais);

            // Đọc From
            Address[] froms = msg.getFrom();
            if (froms != null && froms.length > 0) {
                email.setSender(froms[0].toString());
            }

            // Đọc To
            Address[] tos = msg.getRecipients(Message.RecipientType.TO);
            if (tos != null && tos.length > 0) {
                email.setRecipient(tos[0].toString());
            }

            // Đọc Subject
            email.setSubject(msg.getSubject() != null ? msg.getSubject() : "(Không có chủ đề)");

            // Đọc Sent Date
            if (msg.getSentDate() != null) {
                email.setSentAt(DATE_FORMAT.format(msg.getSentDate()));
            }

            // Phân tích Body và Attachments
            extractParts(msg, email);

            return email;
        } catch (Exception e) {
            System.err.println("[MimeUtil] Lỗi phân tích MIME: " + e.getMessage());
            // Fallback phân tích đơn giản
            parseSimpleHeadersAndBody(mimeContent, email);
            return email;
        }
    }

    private static void extractParts(Part part, EmailMessage email) throws Exception {
        if (part.isMimeType("text/plain")) {
            email.setBody((String) part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition()) || bp.getFileName() != null) {
                    // Tệp đính kèm
                    String filename = bp.getFileName();
                    if (filename != null) {
                        filename = MimeUtility.decodeText(filename);
                    } else {
                        filename = "attachment_" + (i + 1);
                    }
                    InputStream is = bp.getInputStream();
                    byte[] data = is.readAllBytes();
                    email.addAttachment(new Attachment(filename, data.length, data));
                } else if (bp.isMimeType("text/plain") && email.getBody().isEmpty()) {
                    email.setBody((String) bp.getContent());
                } else if (bp.isMimeType("multipart/*")) {
                    extractParts(bp, email);
                }
            }
        } else if (part.getFileName() != null) {
            String filename = MimeUtility.decodeText(part.getFileName());
            InputStream is = part.getInputStream();
            byte[] data = is.readAllBytes();
            email.addAttachment(new Attachment(filename, data.length, data));
        }
    }

    private static void parseSimpleHeadersAndBody(String raw, EmailMessage email) {
        String[] lines = raw.split("\r?\n");
        StringBuilder body = new StringBuilder();
        boolean inHeader = true;

        for (String line : lines) {
            if (inHeader) {
                if (line.trim().isEmpty()) {
                    inHeader = false;
                    continue;
                }
                if (line.toLowerCase().startsWith("from:")) {
                    email.setSender(line.substring(5).trim());
                } else if (line.toLowerCase().startsWith("to:")) {
                    email.setRecipient(line.substring(3).trim());
                } else if (line.toLowerCase().startsWith("subject:")) {
                    email.setSubject(line.substring(8).trim());
                }
            } else {
                body.append(line).append("\n");
            }
        }
        email.setBody(body.toString().trim());
    }
}
