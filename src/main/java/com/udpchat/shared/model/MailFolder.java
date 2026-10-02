package com.udpchat.shared.model;

/**
 * Danh mục hộp thư điện tử theo chuẩn Gmail
 */
public enum MailFolder {
    INBOX("Hộp thư đến"),
    SENT("Đã gửi"),
    DRAFTS("Bản nháp"),
    STARRED("Có gắn dấu sao"),
    TRASH("Thùng rác");

    private final String displayName;

    MailFolder(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
