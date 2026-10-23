package com.udpchat.server.service;

import com.udpchat.server.database.DatabaseManager;
import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Quản lý lưu trữ và thao tác hộp thư điện tử hoàn toàn trên CSDL MySQL theo chuỗi hội thoại
 */
public class MailService {
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AuthService authService;

    public MailService() {
        this(null);
    }

    public MailService(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Gửi email: Lưu vào MySQL và phân phối bản sao vào hòm thư SENT của người gửi và INBOX của người nhận
     */
    public synchronized boolean sendMail(Email email) {
        if (email == null || email.getSender() == null) return false;

        if (email.getId() == null || email.getId().isEmpty()) {
            email.setId(UUID.randomUUID().toString());
        }
        if (email.getSentAt() == null || email.getSentAt().isEmpty()) {
            email.setSentAt(LocalDateTime.now().format(formatter));
        }
        email.setTimestamp(Math.max(email.getTimestamp(), SEQUENCE.incrementAndGet()));

        // Kế thừa threadId nếu là thư trả lời
        if (email.getThreadId() == null || email.getThreadId().isEmpty() || email.getThreadId().equals(email.getId())) {
            if (email.getReplyToId() != null && !email.getReplyToId().isEmpty()) {
                String parentThreadId = findThreadIdOfMail(email.getReplyToId());
                email.setThreadId(parentThreadId != null ? parentThreadId : email.getReplyToId());
            } else {
                email.setThreadId(email.getId());
            }
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lưu bản ghi chính vào bảng emails
                insertEmailRecord(conn, email, false);

                // 2. Lưu vào bảng attachments nếu có đính kèm
                insertAttachments(conn, email.getId(), email.getAttachments());

                // 3. Lưu bản ghi SENT cho người gửi
                insertRecipientRecord(conn, email.getId(), email.getSender(), "SENDER", MailFolder.SENT.name(), email.getSender(), true, false);

                // 4. Phân phối vào INBOX của từng người nhận (To, CC, BCC)
                Set<String> allRecipients = new LinkedHashSet<>();
                if (email.getRecipients() != null) allRecipients.addAll(email.getRecipients());
                if (email.getCc() != null) allRecipients.addAll(email.getCc());
                if (email.getBcc() != null) allRecipients.addAll(email.getBcc());

                for (String rec : allRecipients) {
                    if (rec == null || rec.trim().isEmpty()) continue;
                    String target = rec.trim();
                    String owner = target;
                    // Nếu là email, tra cứu username nội bộ tương ứng để đưa vào inbox người dùng
                    if (target.contains("@") && authService != null) {
                        String mappedUser = authService.getUsernameByEmail(target);
                        if (mappedUser != null && !mappedUser.isEmpty()) {
                            owner = mappedUser;
                        }
                    }

                    String recType = "TO";
                    if (email.getCc() != null && email.getCc().contains(target)) recType = "CC";
                    else if (email.getBcc() != null && email.getBcc().contains(target)) recType = "BCC";

                    insertRecipientRecord(conn, email.getId(), target, recType, MailFolder.INBOX.name(), owner, false, false);
                }

                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                System.err.println("[MailService] Lỗi lưu email: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi kết nối CSDL khi gửi thư: " + e.getMessage());
            return false;
        }
    }

    /**
     * Nhận thư từ bên ngoài (như Gmail IMAP) và lưu vào INBOX của người dùng nội bộ
     */
    public synchronized boolean receiveExternalMail(String username, Email email) {
        if (username == null || email == null) return false;
        if (email.getId() == null || email.getId().isEmpty()) {
            email.setId(UUID.randomUUID().toString());
        }
        if (email.getSentAt() == null || email.getSentAt().isEmpty()) {
            email.setSentAt(LocalDateTime.now().format(formatter));
        }
        email.setTimestamp(Math.max(email.getTimestamp(), SEQUENCE.incrementAndGet()));
        if (email.getThreadId() == null || email.getThreadId().isEmpty()) {
            email.setThreadId(email.getId());
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertEmailRecord(conn, email, false);
                insertAttachments(conn, email.getId(), email.getAttachments());
                insertRecipientRecord(conn, email.getId(), username, "TO", MailFolder.INBOX.name(), username, false, false);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Lấy danh sách email theo thư mục và gộp luồng hội thoại (Conversation Threading)
     */
    public synchronized List<Email> listMails(String username, MailFolder folder, int page, int pageSize) {
        if (username == null) return Collections.emptyList();

        List<Email> rawMails = new ArrayList<>();
        if (folder == MailFolder.STARRED) {
            rawMails.addAll(loadFolderEmails(username, MailFolder.STARRED));
        } else if (folder == MailFolder.INBOX) {
            List<Email> inboxMails = loadFolderEmails(username, MailFolder.INBOX);
            rawMails.addAll(inboxMails);
            Set<String> threadIds = inboxMails.stream()
                    .map(m -> m.getThreadId() != null ? m.getThreadId() : Email.normalizeSubject(m.getSubject()).toLowerCase())
                    .collect(Collectors.toSet());
            List<Email> sentMails = loadFolderEmails(username, MailFolder.SENT);
            for (Email s : sentMails) {
                String sKey = s.getThreadId() != null ? s.getThreadId() : Email.normalizeSubject(s.getSubject()).toLowerCase();
                if (threadIds.contains(sKey)) {
                    rawMails.add(s);
                }
            }
        } else if (folder == MailFolder.SENT) {
            List<Email> sentMails = loadFolderEmails(username, MailFolder.SENT);
            rawMails.addAll(sentMails);
            Set<String> threadIds = sentMails.stream()
                    .map(m -> m.getThreadId() != null ? m.getThreadId() : Email.normalizeSubject(m.getSubject()).toLowerCase())
                    .collect(Collectors.toSet());
            List<Email> inboxMails = loadFolderEmails(username, MailFolder.INBOX);
            for (Email in : inboxMails) {
                String inKey = in.getThreadId() != null ? in.getThreadId() : Email.normalizeSubject(in.getSubject()).toLowerCase();
                if (threadIds.contains(inKey)) {
                    rawMails.add(in);
                }
            }
        } else {
            rawMails.addAll(loadFolderEmails(username, folder));
        }

        // Loại bỏ trùng lặp id nếu một email xuất hiện nhiều lần
        Map<String, Email> uniqueMails = new LinkedHashMap<>();
        for (Email m : rawMails) {
            uniqueMails.put(m.getId(), m);
        }

        // Gom các email cùng luồng (threadId hoặc normalized subject)
        Map<String, List<Email>> threadGroups = new LinkedHashMap<>();
        for (Email m : uniqueMails.values()) {
            String key = m.getThreadId();
            if (key == null || key.isEmpty()) {
                key = Email.normalizeSubject(m.getSubject()).toLowerCase();
            }
            threadGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        List<Email> threadList = new ArrayList<>();
        for (Map.Entry<String, List<Email>> entry : threadGroups.entrySet()) {
            List<Email> msgs = entry.getValue();
            msgs.sort(this::compareMailsChronological);
            Email latest = msgs.get(msgs.size() - 1);

            Email rep = cloneEmail(latest);
            rep.setThreadId(entry.getKey());
            rep.setThreadMessageCount(msgs.size());

            // Gom danh sách người tham gia luồng hội thoại
            List<String> participants = new ArrayList<>();
            for (Email msg : msgs) {
                String senderName = msg.getSender();
                if (username.equalsIgnoreCase(senderName)) {
                    senderName = "tôi";
                }
                if (!participants.contains(senderName)) {
                    participants.add(senderName);
                }
            }
            rep.setThreadParticipants(participants);

            // Trạng thái chưa đọc / có gắn sao / có tệp đính kèm
            rep.setRead(msgs.stream().allMatch(Email::isRead));
            rep.setStarred(msgs.stream().anyMatch(Email::isStarred));
            if (msgs.stream().anyMatch(Email::hasAttachments)) {
                if (!rep.hasAttachments()) {
                    rep.setAttachments(List.of(new Attachment("placeholder", "file", 0, "OTHER", "", "")));
                }
            }

            threadList.add(rep);
        }

        // Sắp xếp luồng mới nhất lên đầu
        threadList.sort(this::compareMailsNewestFirst);

        // Phân trang
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        int startIndex = (page - 1) * pageSize;
        if (startIndex >= threadList.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(startIndex + pageSize, threadList.size());
        return new ArrayList<>(threadList.subList(startIndex, endIndex));
    }

    /**
     * Lấy toàn bộ các email trong cùng một chuỗi hội thoại (Conversation Thread)
     */
    public synchronized List<Email> getThread(String username, String threadId) {
        if (username == null || threadId == null) return Collections.emptyList();

        String actualThreadId = findThreadIdOfMail(threadId);
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            actualThreadId = threadId;
        }

        Map<String, Email> uniqueMails = new LinkedHashMap<>();
        String sql = "SELECT e.*, r.folder, r.is_read AS rec_read, r.is_starred AS rec_starred "
                + "FROM emails e "
                + "JOIN email_recipients r ON e.id = r.email_id "
                + "WHERE r.owner = ? AND (e.thread_id = ? OR e.thread_id = ? OR e.id = ? OR e.reply_to_id = ?) "
                + "ORDER BY e.timestamp_order ASC";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, threadId);
            stmt.setString(3, actualThreadId);
            stmt.setString(4, threadId);
            stmt.setString(5, threadId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Email email = mapResultSetToEmail(conn, rs);
                    uniqueMails.put(email.getId(), email);
                }
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi getThread: " + e.getMessage());
        }

        List<Email> list = new ArrayList<>(uniqueMails.values());

        list.sort(this::compareMailsChronological);

        // Đánh dấu tất cả thư trong thread là đã đọc
        for (Email m : list) {
            if (!m.isRead()) {
                m.setRead(true);
                markRead(username, m.getId(), true);
            }
        }

        return list;
    }

    /**
     * Đọc chi tiết một email theo ID
     */
    public synchronized Email readMail(String username, String mailId) {
        if (username == null || mailId == null) return null;

        String sql = "SELECT e.*, r.folder, r.is_read AS rec_read, r.is_starred AS rec_starred "
                + "FROM emails e "
                + "JOIN email_recipients r ON e.id = r.email_id "
                + "WHERE r.owner = ? AND e.id = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, mailId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Email email = mapResultSetToEmail(conn, rs);
                    if (!email.isRead()) {
                        email.setRead(true);
                        markRead(username, mailId, true);
                    }
                    return email;
                }
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi readMail: " + e.getMessage());
        }
        return null;
    }

    /**
     * Đánh dấu đã đọc / chưa đọc
     */
    public synchronized boolean markRead(String username, String mailId, boolean read) {
        if (username == null || mailId == null) return false;
        String sql = "UPDATE email_recipients SET is_read = ? WHERE owner = ? AND email_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, read);
            stmt.setString(2, username);
            stmt.setString(3, mailId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Gắn sao hoặc bỏ gắn sao
     */
    public synchronized boolean toggleStar(String username, String mailId, boolean starred) {
        if (username == null || mailId == null) return false;
        String sql = "UPDATE email_recipients SET is_starred = ? WHERE owner = ? AND email_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, starred);
            stmt.setString(2, username);
            stmt.setString(3, mailId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Xóa email: Chuyển vào thùng rác (TRASH), hoặc xóa vĩnh viễn nếu đã trong thùng rác
     */
    public synchronized boolean deleteMail(String username, String mailId, boolean permanent) {
        if (username == null || mailId == null) return false;

        try (Connection conn = DatabaseManager.getConnection()) {
            if (permanent) {
                String sql = "DELETE FROM email_recipients WHERE owner = ? AND email_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, username);
                    stmt.setString(2, mailId);
                    return stmt.executeUpdate() > 0;
                }
            } else {
                // Kiểm tra xem hiện tại có đang trong TRASH không
                String checkSql = "SELECT folder FROM email_recipients WHERE owner = ? AND email_id = ?";
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, username);
                    checkStmt.setString(2, mailId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            String currentFolder = rs.getString("folder");
                            if ("TRASH".equalsIgnoreCase(currentFolder)) {
                                // Đã trong thùng rác -> xóa vĩnh viễn
                                return deleteMail(username, mailId, true);
                            }
                        }
                    }
                }

                // Di chuyển vào thùng rác
                String updateSql = "UPDATE email_recipients SET folder = 'TRASH' WHERE owner = ? AND email_id = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, username);
                    updateStmt.setString(2, mailId);
                    return updateStmt.executeUpdate() > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi deleteMail: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lưu hoặc cập nhật bản nháp (DRAFT)
     */
    public synchronized boolean saveDraft(String username, Email draft) {
        if (draft == null || username == null) return false;
        if (draft.getId() == null || draft.getId().isEmpty()) {
            draft.setId(UUID.randomUUID().toString());
        }
        draft.setSender(username);
        draft.setDraft(true);
        draft.setFolder(MailFolder.DRAFTS);
        draft.setSentAt(LocalDateTime.now().format(formatter));
        draft.setTimestamp(Math.max(draft.getTimestamp(), SEQUENCE.incrementAndGet()));
        if (draft.getThreadId() == null || draft.getThreadId().isEmpty()) {
            draft.setThreadId(draft.getId());
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert/Update emails
                insertEmailRecord(conn, draft, true);

                // Insert/Update recipient draft
                String sqlRec = "INSERT INTO email_recipients (email_id, recipient, rec_type, folder, owner, is_read, is_starred) "
                        + "VALUES (?, ?, 'SENDER', 'DRAFTS', ?, TRUE, FALSE) "
                        + "ON DUPLICATE KEY UPDATE folder = 'DRAFTS'";
                try (PreparedStatement stmt = conn.prepareStatement(sqlRec)) {
                    stmt.setString(1, draft.getId());
                    stmt.setString(2, username);
                    stmt.setString(3, username);
                    stmt.executeUpdate();
                }

                insertAttachments(conn, draft.getId(), draft.getAttachments());

                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Tìm kiếm email theo từ khóa
     */
    public synchronized List<Email> searchMail(String username, String keyword) {
        if (username == null || keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Email> list = new ArrayList<>();
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        String sql = "SELECT e.*, r.folder, r.is_read AS rec_read, r.is_starred AS rec_starred "
                + "FROM emails e "
                + "JOIN email_recipients r ON e.id = r.email_id "
                + "WHERE r.owner = ? AND r.folder != 'TRASH' AND ("
                + "LOWER(e.subject) LIKE ? OR LOWER(e.body) LIKE ? OR LOWER(e.sender) LIKE ?"
                + ") ORDER BY e.timestamp_order DESC";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);
            stmt.setString(4, pattern);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToEmail(conn, rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi searchMail: " + e.getMessage());
        }
        return list;
    }

    /**
     * Đếm tổng số luồng thư trong một thư mục
     */
    public synchronized int getMailCount(String username, MailFolder folder) {
        return listMails(username, folder, 1, Integer.MAX_VALUE).size();
    }

    /**
     * Đếm số thư chưa đọc trong hộp thư đến
     */
    public synchronized int getUnreadInboxCount(String username) {
        if (username == null) return 0;
        String sql = "SELECT COUNT(*) FROM email_recipients WHERE owner = ? AND folder = 'INBOX' AND is_read = FALSE";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * Thống kê tổng hợp số lượng email trên toàn hệ thống
     */
    public synchronized Map<String, Object> getServerStats() {
        Map<String, Object> stats = new HashMap<>();
        int totalEmails = 0;
        int todayEmails = 0;
        String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        String sql = "SELECT COUNT(*) AS total, "
                + "SUM(CASE WHEN sent_at LIKE ? THEN 1 ELSE 0 END) AS today "
                + "FROM emails WHERE is_draft = FALSE";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, todayPrefix + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    totalEmails = rs.getInt("total");
                    todayEmails = rs.getInt("today");
                }
            }
        } catch (SQLException ignored) {}

        stats.put("totalEmails", totalEmails);
        stats.put("todayEmails", todayEmails);
        return stats;
    }

    // =========================================================================
    // HELPER DATABASE METHODS
    // =========================================================================

    private void insertEmailRecord(Connection conn, Email email, boolean allowUpdate) throws SQLException {
        String sql = "INSERT INTO emails (id, thread_id, gmail_message_id, sender, sender_email, subject, body, sent_at, timestamp_order, is_read, is_starred, is_draft, reply_to_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + (allowUpdate ? "ON DUPLICATE KEY UPDATE subject=VALUES(subject), body=VALUES(body), sent_at=VALUES(sent_at), is_draft=VALUES(is_draft)" : "");

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email.getId());
            stmt.setString(2, email.getThreadId());
            stmt.setString(3, email.getGmailMessageId());
            stmt.setString(4, email.getSender());
            stmt.setString(5, email.getSenderEmail());
            stmt.setString(6, email.getSubject());
            stmt.setString(7, email.getBody());
            stmt.setString(8, email.getSentAt());
            stmt.setLong(9, email.getTimestamp());
            stmt.setBoolean(10, email.isRead());
            stmt.setBoolean(11, email.isStarred());
            stmt.setBoolean(12, email.isDraft());
            stmt.setString(13, email.getReplyToId());
            stmt.executeUpdate();
        }
    }

    private void insertRecipientRecord(Connection conn, String emailId, String recipient, String recType, String folder, String owner, boolean isRead, boolean isStarred) throws SQLException {
        String sql = "INSERT INTO email_recipients (email_id, recipient, rec_type, folder, owner, is_read, is_starred) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, emailId);
            stmt.setString(2, recipient);
            stmt.setString(3, recType);
            stmt.setString(4, folder);
            stmt.setString(5, owner);
            stmt.setBoolean(6, isRead);
            stmt.setBoolean(7, isStarred);
            stmt.executeUpdate();
        }
    }

    private void insertAttachments(Connection conn, String emailId, List<Attachment> attachments) throws SQLException {
        if (attachments == null || attachments.isEmpty()) return;
        String sql = "INSERT INTO attachments (id, email_id, filename, file_size, content_type, uploaded_by, uploaded_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE filename=VALUES(filename)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Attachment att : attachments) {
                stmt.setString(1, att.getId());
                stmt.setString(2, emailId);
                stmt.setString(3, att.getFilename());
                stmt.setLong(4, att.getSize());
                stmt.setString(5, att.getContentType());
                stmt.setString(6, att.getUploadedBy());
                stmt.setString(7, att.getUploadedAt());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private List<Email> loadFolderEmails(String username, MailFolder folder) {
        List<Email> list = new ArrayList<>();
        String sql;
        if (folder == MailFolder.STARRED) {
            sql = "SELECT e.*, r.folder, r.is_read AS rec_read, r.is_starred AS rec_starred "
                    + "FROM emails e "
                    + "JOIN email_recipients r ON e.id = r.email_id "
                    + "WHERE r.owner = ? AND r.folder != 'TRASH' AND r.is_starred = TRUE "
                    + "ORDER BY e.timestamp_order DESC";
        } else {
            sql = "SELECT e.*, r.folder, r.is_read AS rec_read, r.is_starred AS rec_starred "
                    + "FROM emails e "
                    + "JOIN email_recipients r ON e.id = r.email_id "
                    + "WHERE r.owner = ? AND r.folder = ? "
                    + "ORDER BY e.timestamp_order DESC";
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            if (folder != MailFolder.STARRED) {
                stmt.setString(2, folder.name());
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToEmail(conn, rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MailService] Lỗi loadFolderEmails: " + e.getMessage());
        }
        return list;
    }

    private Email mapResultSetToEmail(Connection conn, ResultSet rs) throws SQLException {
        Email email = new Email();
        String id = rs.getString("id");
        email.setId(id);
        email.setThreadId(rs.getString("thread_id"));
        email.setGmailMessageId(rs.getString("gmail_message_id"));
        email.setSender(rs.getString("sender"));
        email.setSenderEmail(rs.getString("sender_email"));
        email.setSubject(rs.getString("subject"));
        email.setBody(rs.getString("body"));
        email.setSentAt(rs.getString("sent_at"));
        email.setTimestamp(rs.getLong("timestamp_order"));
        email.setDraft(rs.getBoolean("is_draft"));
        email.setReplyToId(rs.getString("reply_to_id"));

        // Lấy trạng thái folder/read/starred từ bảng email_recipients
        try {
            String folderStr = rs.getString("folder");
            if (folderStr != null) {
                email.setFolder(MailFolder.valueOf(folderStr));
            }
            email.setRead(rs.getBoolean("rec_read"));
            email.setStarred(rs.getBoolean("rec_starred"));
        } catch (Exception ignored) {
            email.setRead(rs.getBoolean("is_read"));
            email.setStarred(rs.getBoolean("is_starred"));
        }

        // Tải danh sách người nhận
        loadRecipientsForEmail(conn, email);

        // Tải danh sách file đính kèm
        loadAttachmentsForEmail(conn, email);

        return email;
    }

    private void loadRecipientsForEmail(Connection conn, Email email) {
        String sql = "SELECT recipient, rec_type FROM email_recipients WHERE email_id = ? AND rec_type != 'SENDER'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> to = new ArrayList<>();
                List<String> cc = new ArrayList<>();
                List<String> bcc = new ArrayList<>();
                while (rs.next()) {
                    String rec = rs.getString("recipient");
                    String type = rs.getString("rec_type");
                    if ("CC".equalsIgnoreCase(type)) {
                        if (!cc.contains(rec)) cc.add(rec);
                    } else if ("BCC".equalsIgnoreCase(type)) {
                        if (!bcc.contains(rec)) bcc.add(rec);
                    } else {
                        if (!to.contains(rec)) to.add(rec);
                    }
                }
                email.setRecipients(to);
                email.setCc(cc);
                email.setBcc(bcc);
            }
        } catch (SQLException ignored) {}
    }

    private void loadAttachmentsForEmail(Connection conn, Email email) {
        String sql = "SELECT id, filename, file_size, content_type, uploaded_by, uploaded_at FROM attachments WHERE email_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                List<Attachment> atts = new ArrayList<>();
                while (rs.next()) {
                    Attachment att = new Attachment(
                            rs.getString("id"),
                            rs.getString("filename"),
                            rs.getLong("file_size"),
                            rs.getString("content_type"),
                            rs.getString("uploaded_by"),
                            rs.getString("uploaded_at")
                    );
                    atts.add(att);
                }
                email.setAttachments(atts);
            }
        } catch (SQLException ignored) {}
    }

    private String findThreadIdOfMail(String mailId) {
        String sql = "SELECT thread_id FROM emails WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mailId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("thread_id");
            }
        } catch (SQLException ignored) {}
        return null;
    }

    private Email cloneEmail(Email src) {
        Email copy = new Email();
        copy.setId(src.getId());
        copy.setThreadId(src.getThreadId());
        copy.setGmailMessageId(src.getGmailMessageId());
        copy.setSender(src.getSender());
        copy.setSenderEmail(src.getSenderEmail());
        copy.setRecipients(new ArrayList<>(src.getRecipients()));
        copy.setCc(new ArrayList<>(src.getCc()));
        copy.setBcc(new ArrayList<>(src.getBcc()));
        copy.setSubject(src.getSubject());
        copy.setBody(src.getBody());
        copy.setSentAt(src.getSentAt());
        copy.setTimestamp(src.getTimestamp());
        copy.setFolder(src.getFolder());
        copy.setDraft(src.isDraft());
        copy.setReplyToId(src.getReplyToId());
        copy.setAttachments(new ArrayList<>(src.getAttachments()));
        return copy;
    }

    private int compareMailsChronological(Email a, Email b) {
        if (a.getTimestamp() > 0 && b.getTimestamp() > 0 && a.getTimestamp() != b.getTimestamp()) {
            return Long.compare(a.getTimestamp(), b.getTimestamp());
        }
        String timeA = a.getSentAt() != null ? a.getSentAt() : "";
        String timeB = b.getSentAt() != null ? b.getSentAt() : "";
        int cmp = timeA.compareTo(timeB);
        if (cmp != 0) return cmp;
        if (b.getReplyToId() != null && b.getReplyToId().equalsIgnoreCase(a.getId())) return -1;
        if (a.getReplyToId() != null && a.getReplyToId().equalsIgnoreCase(b.getId())) return 1;
        return 0;
    }

    private int compareMailsNewestFirst(Email a, Email b) {
        return compareMailsChronological(b, a);
    }
}
