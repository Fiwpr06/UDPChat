package com.udpchat.shared.model;

import java.io.Serializable;
import java.util.Locale;

/**
 * Đại diện cho tệp đính kèm trong thư điện tử
 */
public class Attachment implements Serializable {
    private String id;              // UUID duy nhất cho tệp lưu trên server
    private String filename;        // Tên tệp gốc kèm đuôi (vd: BaoCao.pdf, HinhAnh.png)
    private long size;              // Dung lượng tính bằng bytes
    private String contentType;     // IMAGE, PDF, DOCUMENT, ARCHIVE, OTHER
    private String uploadedBy;      // Tên người dùng tải lên
    private String uploadedAt;      // Thời gian tải lên định dạng chuỗi

    public Attachment() {}

    public Attachment(String id, String filename, long size, String contentType, String uploadedBy, String uploadedAt) {
        this.id = id;
        this.filename = filename;
        this.size = size;
        this.contentType = contentType;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public static String detectContentType(String filename) {
        if (filename == null) return "OTHER";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        } else if (lower.endsWith(".pdf")) {
            return "PDF";
        } else if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "DOCUMENT";
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return "ARCHIVE";
        }
        return "OTHER";
    }

    public String formatSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    public boolean isImage() {
        return "IMAGE".equalsIgnoreCase(contentType);
    }

    /**
     * Rút gọn tên tệp ở giữa nếu quá dài, hiển thị phần đầu và phần đuôi kèm định dạng tệp
     * Ví dụ: 1786325328006_...5e8231.jpg -> 17863253280...5e8231.jpg
     */
    public static String truncateFileNameMiddle(String filename, int maxLength) {
        if (filename == null) return "";
        if (filename.length() <= maxLength) return filename;

        int extIndex = filename.lastIndexOf('.');
        String ext = "";
        String base = filename;
        if (extIndex > 0 && extIndex < filename.length() - 1) {
            ext = filename.substring(extIndex);
            base = filename.substring(0, extIndex);
        }

        int extLen = ext.length();
        if (extLen >= maxLength - 5) {
            return filename.substring(0, Math.max(1, maxLength - 3)) + "...";
        }

        int remaining = maxLength - extLen - 3;
        if (remaining <= 2) {
            return filename.substring(0, Math.max(1, maxLength - 3)) + "...";
        }

        int prefixLen = (int) Math.ceil(remaining * 0.6);
        int suffixLen = remaining - prefixLen;

        String prefix = base.substring(0, prefixLen);
        String suffix = suffixLen > 0 ? base.substring(base.length() - suffixLen) : "";

        return prefix + "..." + suffix + ext;
    }

    public String getTruncatedFilename(int maxLength) {
        return truncateFileNameMiddle(this.filename, maxLength);
    }

    public String getTruncatedFilename() {
        return truncateFileNameMiddle(this.filename, 24);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public String getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(String uploadedAt) { this.uploadedAt = uploadedAt; }
}
