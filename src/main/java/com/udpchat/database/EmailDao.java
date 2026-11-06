package com.udpchat.database;

import com.udpchat.common.model.Attachment;
import com.udpchat.common.model.EmailMessage;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Thao tác cơ sở dữ liệu cho Thư điện tử (Emails)
 */
public class EmailDao {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AttachmentDao attachmentDao = new AttachmentDao();

    public int saveEmail(EmailMessage email) {
        if (email == null) return -1;
        if (email.getSentAt() == null || email.getSentAt().isEmpty()) {
            email.setSentAt(LocalDateTime.now().format(FORMATTER));
        }

        String sql = "INSERT INTO emails (sender, recipient, subject, body, sent_at, is_read) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, email.getSender() != null ? email.getSender().trim() : "unknown@localhost");
            stmt.setString(2, email.getRecipient() != null ? email.getRecipient().trim() : "unknown@localhost");
            stmt.setString(3, email.getSubject());
            stmt.setString(4, email.getBody());
            stmt.setString(5, email.getSentAt());
            stmt.setBoolean(6, email.isRead());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                int generatedId = -1;
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        email.setId(generatedId);
                    }
                }

                // Lưu các tệp đính kèm nếu có
                if (generatedId > 0 && email.hasAttachments()) {
                    for (Attachment att : email.getAttachments()) {
                        attachmentDao.saveAttachment(generatedId, att);
                    }
                }

                return generatedId;
            }
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi lưu email: " + e.getMessage());
        }
        return -1;
    }

    public List<EmailMessage> getInbox(String username) {
        List<EmailMessage> list = new ArrayList<>();
        if (username == null || username.trim().isEmpty()) return list;

        String u = username.trim().toLowerCase();
        // Nhận thư nếu recipient khớp username, username@localhost hoặc email của user
        String sql = "SELECT id, sender, recipient, subject, body, sent_at, is_read FROM emails " +
                     "WHERE LOWER(recipient) = ? OR LOWER(recipient) = ? OR LOWER(recipient) LIKE ? " +
                     "ORDER BY id DESC";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, u);
            stmt.setString(2, u + "@localhost");
            stmt.setString(3, u + "@%");

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    EmailMessage em = new EmailMessage();
                    em.setId(rs.getInt("id"));
                    em.setSender(rs.getString("sender"));
                    em.setRecipient(rs.getString("recipient"));
                    em.setSubject(rs.getString("subject"));
                    em.setBody(rs.getString("body"));
                    em.setSentAt(rs.getString("sent_at"));
                    em.setRead(rs.getBoolean("is_read"));

                    // Nạp attachments
                    em.setAttachments(attachmentDao.getAttachmentsByEmailId(em.getId()));
                    list.add(em);
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi lấy hộp thư đến: " + e.getMessage());
        }
        return list;
    }

    public List<EmailMessage> getSent(String username) {
        List<EmailMessage> list = new ArrayList<>();
        if (username == null || username.trim().isEmpty()) return list;

        String u = username.trim().toLowerCase();
        String sql = "SELECT id, sender, recipient, subject, body, sent_at, is_read FROM emails " +
                     "WHERE LOWER(sender) = ? OR LOWER(sender) = ? OR LOWER(sender) LIKE ? " +
                     "ORDER BY id DESC";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, u);
            stmt.setString(2, u + "@localhost");
            stmt.setString(3, u + "@%");

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    EmailMessage em = new EmailMessage();
                    em.setId(rs.getInt("id"));
                    em.setSender(rs.getString("sender"));
                    em.setRecipient(rs.getString("recipient"));
                    em.setSubject(rs.getString("subject"));
                    em.setBody(rs.getString("body"));
                    em.setSentAt(rs.getString("sent_at"));
                    em.setRead(rs.getBoolean("is_read"));

                    em.setAttachments(attachmentDao.getAttachmentsByEmailId(em.getId()));
                    list.add(em);
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi lấy thư đã gửi: " + e.getMessage());
        }
        return list;
    }

    public EmailMessage getEmailById(int id) {
        String sql = "SELECT id, sender, recipient, subject, body, sent_at, is_read FROM emails WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    EmailMessage em = new EmailMessage();
                    em.setId(rs.getInt("id"));
                    em.setSender(rs.getString("sender"));
                    em.setRecipient(rs.getString("recipient"));
                    em.setSubject(rs.getString("subject"));
                    em.setBody(rs.getString("body"));
                    em.setSentAt(rs.getString("sent_at"));
                    em.setRead(rs.getBoolean("is_read"));
                    em.setAttachments(attachmentDao.getAttachmentsByEmailId(em.getId()));
                    return em;
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi lấy email theo ID: " + e.getMessage());
        }
        return null;
    }

    public boolean markAsRead(int id) {
        String sql = "UPDATE emails SET is_read = 1 WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi đánh dấu đã đọc: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteEmail(int id) {
        String sql = "DELETE FROM emails WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi xóa email: " + e.getMessage());
            return false;
        }
    }

    public int countEmails() {
        String sql = "SELECT COUNT(*) FROM emails";
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("[EmailDao] Lỗi đếm email: " + e.getMessage());
        }
        return 0;
    }
}
