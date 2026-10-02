package com.udpchat.shared.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Đại diện cho thư điện tử hoàn chỉnh theo mô hình hội thoại (Conversation Threading) của Gmail
 */
public class Email implements Serializable {
    private String id;
    private String threadId;          // ID luồng hội thoại chung cho chuỗi thư
    private int threadMessageCount = 1; // Số lượng thư trong luồng hội thoại
    private List<String> threadParticipants = new ArrayList<>(); // Danh sách người tham gia luồng
    private long timestamp = System.currentTimeMillis(); // Mốc thời gian đơn điệu để sắp xếp chính xác luồng

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
        this.threadId = this.id;
    }

    public Email(String sender, List<String> recipients, String subject, String body) {
        this();
        this.sender = sender;
        if (recipients != null) this.recipients = new ArrayList<>(recipients);
        this.subject = subject;
        this.body = body;
    }

    /**
     * Chuẩn hóa tiêu đề: loại bỏ các tiền tố Re:, Fwd:, Fw:, RE:, FWD: lặp lại
     */
    public static String normalizeSubject(String subj) {
        if (subj == null || subj.trim().isEmpty()) {
            return "(Không có chủ đề)";
        }
        String clean = subj.trim();
        Pattern prefixPattern = Pattern.compile("^(re|fwd|fw)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
        while (prefixPattern.matcher(clean).find()) {
            clean = prefixPattern.matcher(clean).replaceFirst("").trim();
        }
        return clean.isEmpty() ? "(Không có chủ đề)" : clean;
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

    public String getThreadId() {
        return (threadId != null && !threadId.isEmpty()) ? threadId : id;
    }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public int getThreadMessageCount() { return Math.max(1, threadMessageCount); }
    public void setThreadMessageCount(int threadMessageCount) { this.threadMessageCount = threadMessageCount; }

    public List<String> getThreadParticipants() { return threadParticipants; }
    public void setThreadParticipants(List<String> threadParticipants) {
        this.threadParticipants = threadParticipants != null ? threadParticipants : new ArrayList<>();
    }

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

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
