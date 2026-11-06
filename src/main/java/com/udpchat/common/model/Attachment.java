package com.udpchat.common.model;

/**
 * Model biểu diễn tệp đính kèm trong thư điện tử
 */
public class Attachment {
    private int id;
    private int emailId;
    private String filename;
    private long fileSize;
    private String filePath;
    private byte[] data;

    public Attachment() {
    }

    public Attachment(String filename, long fileSize, byte[] data) {
        this.filename = filename;
        this.fileSize = fileSize;
        this.data = data;
    }

    public Attachment(int id, int emailId, String filename, long fileSize, String filePath) {
        this.id = id;
        this.emailId = emailId;
        this.filename = filename;
        this.fileSize = fileSize;
        this.filePath = filePath;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getEmailId() {
        return emailId;
    }

    public void setEmailId(int emailId) {
        this.emailId = emailId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getFormattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
    }

    @Override
    public String toString() {
        return filename + " (" + getFormattedSize() + ")";
    }
}
