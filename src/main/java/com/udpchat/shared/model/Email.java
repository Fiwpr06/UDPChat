package com.udpchat.shared.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Đại diện cho thư điện tử hoàn chỉnh theo mô hình Gmail
 */
public class Email implements Serializable {
    private String id;
    private String sender;
    private List<String> recipients = new ArrayList<>();
    private List<String> cc = new ArrayList<>();
    private List<String> bcc = new ArrayList<>();
    private String subject;
    private String body;
    private String sentAt;
    private boolean read;
    private boolean starred;
    private boolean draft;
    private MailFolder folder;
    private List<Attachment> attachments = new ArrayList<>();
    private String replyToId;

    public Email() {
        this.id = UUID.randomUUID().toString();
    }

    public Email(String sender, List<String> recipients, String subject, String body) {
        this();
        this.sender = sender;
        if (recipients != null) this.recipients = new ArrayList<>(recipients);
        this.subject = subject;
        this.body = body;
    }

    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    public int getAttachmentCount() {
        return attachments != null ? attachments.size() : 0;
    }

    public boolean hasImages() {
        if (attachments == null) return false;
        return attachments.stream().anyMatch(Attachment::isImage);
    }

    public String getPreview(int maxLen) {
        if (body == null || body.trim().isEmpty()) {
            return "(Không có nội dung)";
        }
        String clean = body.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLen) {
            return clean;
        }
        return clean.substring(0, maxLen) + "...";
    }

    public String getRecipientsDisplay() {
        if (recipients == null || recipients.isEmpty()) {
            return "";
        }
        return String.join(", ", recipients);
    }

    public String getCcDisplay() {
        if (cc == null || cc.isEmpty()) {
            return "";
        }
        return String.join(", ", cc);
    }

    public String getBccDisplay() {
        if (bcc == null || bcc.isEmpty()) {
            return "";
        }
        return String.join(", ", bcc);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients != null ? recipients : new ArrayList<>(); }

    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = cc != null ? cc : new ArrayList<>(); }

    public List<String> getBcc() { return bcc; }
    public void setBcc(List<String> bcc) { this.bcc = bcc != null ? bcc : new ArrayList<>(); }

    public String getSubject() { return subject != null ? subject : "(Không có chủ đề)"; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body != null ? body : ""; }
    public void setBody(String body) { this.body = body; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }

    public MailFolder getFolder() { return folder; }
    public void setFolder(MailFolder folder) { this.folder = folder; }

    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) { this.attachments = attachments != null ? attachments : new ArrayList<>(); }

    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
}
