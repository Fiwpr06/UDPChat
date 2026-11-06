package com.udpchat.common.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Model biểu diễn một thư điện tử (Email) trong hệ thống
 */
public class EmailMessage {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private int id;
    private String sender;
    private String recipient;
    private String subject;
    private String body;
    private String sentAt;
    private boolean isRead;
    private List<Attachment> attachments = new ArrayList<>();

    public EmailMessage() {
        this.sentAt = LocalDateTime.now().format(FORMATTER);
    }

    public EmailMessage(String sender, String recipient, String subject, String body) {
        this();
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject != null && !subject.trim().isEmpty() ? subject : "(Không có chủ đề)";
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getSentAt() {
        return sentAt;
    }

    public void setSentAt(String sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    public void addAttachment(Attachment attachment) {
        if (attachment != null) {
            this.attachments.add(attachment);
        }
    }

    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    @Override
    public String toString() {
        return "[" + sentAt + "] Từ: " + sender + " | Tiêu đề: " + getSubject();
    }
}
