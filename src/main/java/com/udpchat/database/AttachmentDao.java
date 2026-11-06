package com.udpchat.database;

import com.udpchat.common.model.Attachment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Thao tác cơ sở dữ liệu và lưu trữ tệp đính kèm (Attachments)
 */
public class AttachmentDao {
    private static final String ATTACH_DIR = "server-data/attachments";

    public AttachmentDao() {
        try {
            Files.createDirectories(Paths.get(ATTACH_DIR));
        } catch (Exception ignored) {}
    }

    public boolean saveAttachment(int emailId, Attachment attachment) {
        if (attachment == null || attachment.getFilename() == null) return false;

        String safeName = System.currentTimeMillis() + "_" + attachment.getFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
        Path destPath = Paths.get(ATTACH_DIR, safeName);

        try {
            if (attachment.getData() != null && attachment.getData().length > 0) {
                Files.write(destPath, attachment.getData());
            } else {
                Files.createFile(destPath);
            }
        } catch (Exception e) {
            System.err.println("[AttachmentDao] Lỗi lưu file đính kèm: " + e.getMessage());
            return false;
        }

        String sql = "INSERT INTO attachments (email_id, filename, file_size, file_path) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, emailId);
            stmt.setString(2, attachment.getFilename());
            stmt.setLong(3, attachment.getFileSize() > 0 ? attachment.getFileSize() : (attachment.getData() != null ? attachment.getData().length : 0));
            stmt.setString(4, destPath.toString());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        attachment.setId(rs.getInt(1));
                    }
                }
                attachment.setFilePath(destPath.toString());
                return true;
            }
        } catch (SQLException e) {
            System.err.println("[AttachmentDao] Lỗi ghi DB tệp đính kèm: " + e.getMessage());
        }
        return false;
    }

    public List<Attachment> getAttachmentsByEmailId(int emailId) {
        List<Attachment> list = new ArrayList<>();
        String sql = "SELECT id, email_id, filename, file_size, file_path FROM attachments WHERE email_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, emailId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Attachment att = new Attachment(
                            rs.getInt("id"),
                            rs.getInt("email_id"),
                            rs.getString("filename"),
                            rs.getLong("file_size"),
                            rs.getString("file_path")
                    );
                    // Đọc data nếu file tồn tại
                    if (att.getFilePath() != null) {
                        File f = new File(att.getFilePath());
                        if (f.exists()) {
                            att.setData(Files.readAllBytes(f.toPath()));
                        }
                    }
                    list.add(att);
                }
            }
        } catch (Exception e) {
            System.err.println("[AttachmentDao] Lỗi lấy tệp đính kèm: " + e.getMessage());
        }
        return list;
    }
}
