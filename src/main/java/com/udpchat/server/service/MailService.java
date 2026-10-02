package com.udpchat.server.service;

import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.util.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Quản lý lưu trữ và thao tác hộp thư điện tử trên Server
 */
public class MailService {
    private final Path mailboxRoot;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MailService() {
        mailboxRoot = Paths.get("server-data", "mailbox");
        try {
            Files.createDirectories(mailboxRoot);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Path getUserFolder(String username, MailFolder folder) {
        String safeUser = username.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path folderPath = mailboxRoot.resolve(safeUser).resolve(folder.name().toLowerCase());
        try {
            Files.createDirectories(folderPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return folderPath;
    }

    /**
     * Gửi email: Lưu vào hộp Sent của người gửi và Inbox của người nhận
     */
    public synchronized boolean sendMail(Email email) {
        if (email == null || email.getSender() == null) return false;

        if (email.getId() == null || email.getId().isEmpty()) {
            email.setId(UUID.randomUUID().toString());
        }
        if (email.getSentAt() == null || email.getSentAt().isEmpty()) {
            email.setSentAt(LocalDateTime.now().format(formatter));
        }

        // 1. Lưu bản sao vào thư mục SENT của người gửi
        email.setFolder(MailFolder.SENT);
        email.setRead(true);
        saveEmailToFile(email.getSender(), MailFolder.SENT, email);

        // 2. Gom tất cả người nhận (To + CC + BCC)
        Set<String> allRecipients = new HashSet<>();
        if (email.getRecipients() != null) allRecipients.addAll(email.getRecipients());
        if (email.getCc() != null) allRecipients.addAll(email.getCc());
        if (email.getBcc() != null) allRecipients.addAll(email.getBcc());

        // 3. Lưu vào INBOX của từng người nhận
        for (String recipient : allRecipients) {
            if (recipient == null || recipient.trim().isEmpty()) continue;
            String trimmedRecipient = recipient.trim();
            // Tạo bản sao cho người nhận
            Email recipientCopy = JsonUtil.fromJson(JsonUtil.toJson(email), Email.class);
            recipientCopy.setFolder(MailFolder.INBOX);
            recipientCopy.setRead(false);
            recipientCopy.setStarred(false);
            // Xóa BCC nếu người nhận không phải người trong BCC
            saveEmailToFile(trimmedRecipient, MailFolder.INBOX, recipientCopy);
        }

        return true;
    }

    /**
     * Lấy danh sách email theo thư mục kèm phân trang
     */
    public synchronized List<Email> listMails(String username, MailFolder folder, int page, int pageSize) {
        if (username == null) return Collections.emptyList();
        List<Email> allMails = new ArrayList<>();

        if (folder == MailFolder.STARRED) {
            // Lấy từ INBOX và SENT các thư có gắn sao
            allMails.addAll(loadFolderMails(username, MailFolder.INBOX));
            allMails.addAll(loadFolderMails(username, MailFolder.SENT));
            allMails = allMails.stream().filter(Email::isStarred).collect(Collectors.toList());
        } else {
            allMails = loadFolderMails(username, folder);
        }

        // Sắp xếp thư mới nhất lên đầu
        allMails.sort((a, b) -> {
            String timeA = a.getSentAt() != null ? a.getSentAt() : "";
            String timeB = b.getSentAt() != null ? b.getSentAt() : "";
            return timeB.compareTo(timeA);
        });

        // Phân trang
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        int startIndex = (page - 1) * pageSize;
        if (startIndex >= allMails.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(startIndex + pageSize, allMails.size());
        return allMails.subList(startIndex, endIndex);
    }

    /**
     * Đếm tổng số thư trong một thư mục
     */
    public synchronized int getMailCount(String username, MailFolder folder) {
        if (folder == MailFolder.STARRED) {
            List<Email> list = new ArrayList<>();
            list.addAll(loadFolderMails(username, MailFolder.INBOX));
            list.addAll(loadFolderMails(username, MailFolder.SENT));
            return (int) list.stream().filter(Email::isStarred).count();
        }
        return loadFolderMails(username, folder).size();
    }

    /**
     * Đếm số thư chưa đọc trong hộp thư đến
     */
    public synchronized int getUnreadInboxCount(String username) {
        return (int) loadFolderMails(username, MailFolder.INBOX).stream()
                .filter(m -> !m.isRead())
                .count();
    }

    /**
     * Đọc chi tiết một email theo ID
     */
    public synchronized Email readMail(String username, String mailId) {
        if (username == null || mailId == null) return null;
        for (MailFolder folder : MailFolder.values()) {
            Path folderPath = getUserFolder(username, folder);
            Path filePath = folderPath.resolve(mailId + ".json");
            if (Files.exists(filePath)) {
                Email email = loadEmailFromFile(filePath);
                if (email != null && folder == MailFolder.INBOX && !email.isRead()) {
                    email.setRead(true);
                    saveEmailToFile(username, folder, email);
                }
                return email;
            }
        }
        return null;
    }

    /**
     * Đánh dấu thư đã đọc hoặc chưa đọc
     */
    public synchronized boolean markRead(String username, String mailId, boolean read) {
        for (MailFolder folder : MailFolder.values()) {
            Path filePath = getUserFolder(username, folder).resolve(mailId + ".json");
            if (Files.exists(filePath)) {
                Email email = loadEmailFromFile(filePath);
                if (email != null) {
                    email.setRead(read);
                    saveEmailToFile(username, folder, email);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gắn sao hoặc bỏ gắn sao
     */
    public synchronized boolean toggleStar(String username, String mailId, boolean starred) {
        for (MailFolder folder : MailFolder.values()) {
            Path filePath = getUserFolder(username, folder).resolve(mailId + ".json");
            if (Files.exists(filePath)) {
                Email email = loadEmailFromFile(filePath);
                if (email != null) {
                    email.setStarred(starred);
                    saveEmailToFile(username, folder, email);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Xóa email: Chuyển vào Thùng rác (TRASH), hoặc xóa vĩnh viễn nếu đã trong TRASH
     */
    public synchronized boolean deleteMail(String username, String mailId, boolean permanent) {
        Path trashFolder = getUserFolder(username, MailFolder.TRASH);
        Path inTrashPath = trashFolder.resolve(mailId + ".json");

        if (permanent || Files.exists(inTrashPath)) {
            // Xóa vĩnh viễn
            try {
                return Files.deleteIfExists(inTrashPath);
            } catch (IOException e) {
                return false;
            }
        }

        // Tìm từ các thư mục khác và chuyển vào Trash
        for (MailFolder folder : MailFolder.values()) {
            if (folder == MailFolder.TRASH) continue;
            Path sourceFile = getUserFolder(username, folder).resolve(mailId + ".json");
            if (Files.exists(sourceFile)) {
                try {
                    Email email = loadEmailFromFile(sourceFile);
                    if (email != null) {
                        email.setFolder(MailFolder.TRASH);
                        saveEmailToFile(username, MailFolder.TRASH, email);
                    }
                    Files.deleteIfExists(sourceFile);
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return false;
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
        return saveEmailToFile(username, MailFolder.DRAFTS, draft);
    }

    /**
     * Tìm kiếm email theo từ khóa trong tiêu đề, nội dung hoặc người gửi
     */
    public synchronized List<Email> searchMail(String username, String keyword) {
        if (username == null || keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String lowerKey = keyword.trim().toLowerCase();
        List<Email> allMails = new ArrayList<>();
        allMails.addAll(loadFolderMails(username, MailFolder.INBOX));
        allMails.addAll(loadFolderMails(username, MailFolder.SENT));
        allMails.addAll(loadFolderMails(username, MailFolder.DRAFTS));

        return allMails.stream()
                .filter(m -> (m.getSubject() != null && m.getSubject().toLowerCase().contains(lowerKey))
                        || (m.getBody() != null && m.getBody().toLowerCase().contains(lowerKey))
                        || (m.getSender() != null && m.getSender().toLowerCase().contains(lowerKey))
                        || (m.getRecipientsDisplay() != null && m.getRecipientsDisplay().toLowerCase().contains(lowerKey)))
                .sorted((a, b) -> {
                    String timeA = a.getSentAt() != null ? a.getSentAt() : "";
                    String timeB = b.getSentAt() != null ? b.getSentAt() : "";
                    return timeB.compareTo(timeA);
                })
                .collect(Collectors.toList());
    }

    /**
     * Thống kê tổng hợp số lượng email trên toàn hệ thống
     */
    public synchronized Map<String, Object> getServerStats() {
        Map<String, Object> stats = new HashMap<>();
        int totalEmails = 0;
        int todayEmails = 0;
        String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        try {
            if (Files.exists(mailboxRoot)) {
                try (var userStream = Files.list(mailboxRoot)) {
                    for (Path userDir : userStream.toList()) {
                        if (Files.isDirectory(userDir)) {
                            Path sentFolder = userDir.resolve("sent");
                            if (Files.exists(sentFolder)) {
                                try (var mailStream = Files.list(sentFolder)) {
                                    for (Path mailPath : mailStream.toList()) {
                                        totalEmails++;
                                        Email email = loadEmailFromFile(mailPath);
                                        if (email != null && email.getSentAt() != null && email.getSentAt().startsWith(todayPrefix)) {
                                            todayEmails++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        stats.put("totalEmails", totalEmails);
        stats.put("todayEmails", todayEmails);
        return stats;
    }

    // Helper functions
    private boolean saveEmailToFile(String username, MailFolder folder, Email email) {
        Path folderPath = getUserFolder(username, folder);
        Path filePath = folderPath.resolve(email.getId() + ".json");
        try {
            String json = JsonUtil.toJson(email);
            Files.writeString(filePath, json, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private Email loadEmailFromFile(Path filePath) {
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            return JsonUtil.fromJson(json, Email.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Email> loadFolderMails(String username, MailFolder folder) {
        Path folderPath = getUserFolder(username, folder);
        List<Email> list = new ArrayList<>();
        if (!Files.exists(folderPath)) return list;
        try (var stream = Files.list(folderPath)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                Email email = loadEmailFromFile(p);
                if (email != null) {
                    list.add(email);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }
}
