package com.udpchat.server.service;

import com.udpchat.shared.model.Attachment;
import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Quản lý lưu trữ và thao tác hộp thư điện tử trên Server theo chuỗi hội thoại (Gmail Threading)
 */
public class MailService {
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());
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
     * Gửi email: Tự động gắn/kế thừa threadId và lưu vào hộp Sent của người gửi và Inbox của người nhận
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

        // Kế thừa threadId nếu là thư trả lời hoặc chuyển tiếp
        if (email.getThreadId() == null || email.getThreadId().isEmpty() || email.getThreadId().equals(email.getId())) {
            if (email.getReplyToId() != null && !email.getReplyToId().isEmpty()) {
                Email parent = findMailAnywhere(email.getReplyToId());
                if (parent != null && parent.getThreadId() != null && !parent.getThreadId().isEmpty()) {
                    email.setThreadId(parent.getThreadId());
                } else {
                    email.setThreadId(email.getReplyToId());
                }
            } else {
                email.setThreadId(email.getId());
            }
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
            Email recipientCopy = JsonUtil.fromJson(JsonUtil.toJson(email), Email.class);
            recipientCopy.setFolder(MailFolder.INBOX);
            recipientCopy.setRead(false);
            recipientCopy.setStarred(false);
            saveEmailToFile(trimmedRecipient, MailFolder.INBOX, recipientCopy);
        }

        return true;
    }

    /**
     * Lấy danh sách thư theo thư mục, tự động GỘP THƯ THEO CHUỖI HỘI THOẠI (Conversation Threading)
     */
    public synchronized List<Email> listMails(String username, MailFolder folder, int page, int pageSize) {
        if (username == null) return Collections.emptyList();
        List<Email> allMails = new ArrayList<>();

        if (folder == MailFolder.STARRED) {
            allMails.addAll(loadFolderMails(username, MailFolder.INBOX));
            allMails.addAll(loadFolderMails(username, MailFolder.SENT));
            allMails = allMails.stream().filter(Email::isStarred).collect(Collectors.toList());
        } else if (folder == MailFolder.INBOX) {
            allMails.addAll(loadFolderMails(username, MailFolder.INBOX));
            Set<String> threadIdsInFolder = allMails.stream()
                    .map(m -> m.getThreadId() != null ? m.getThreadId() : Email.normalizeSubject(m.getSubject()).toLowerCase())
                    .collect(Collectors.toSet());
            List<Email> sentMails = loadFolderMails(username, MailFolder.SENT);
            for (Email s : sentMails) {
                String sKey = s.getThreadId() != null ? s.getThreadId() : Email.normalizeSubject(s.getSubject()).toLowerCase();
                if (threadIdsInFolder.contains(sKey)) {
                    allMails.add(s);
                }
            }
        } else if (folder == MailFolder.SENT) {
            allMails.addAll(loadFolderMails(username, MailFolder.SENT));
            Set<String> threadIdsInFolder = allMails.stream()
                    .map(m -> m.getThreadId() != null ? m.getThreadId() : Email.normalizeSubject(m.getSubject()).toLowerCase())
                    .collect(Collectors.toSet());
            List<Email> inboxMails = loadFolderMails(username, MailFolder.INBOX);
            for (Email in : inboxMails) {
                String inKey = in.getThreadId() != null ? in.getThreadId() : Email.normalizeSubject(in.getSubject()).toLowerCase();
                if (threadIdsInFolder.contains(inKey)) {
                    allMails.add(in);
                }
            }
        } else {
            allMails = loadFolderMails(username, folder);
        }

        // Gom các email cùng luồng (threadId hoặc normalized subject)
        Map<String, List<Email>> threadGroups = new LinkedHashMap<>();
        for (Email m : allMails) {
            String key = m.getThreadId();
            if (key == null || key.isEmpty()) {
                key = Email.normalizeSubject(m.getSubject()).toLowerCase();
            }
            threadGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        List<Email> threadList = new ArrayList<>();
        for (Map.Entry<String, List<Email>> entry : threadGroups.entrySet()) {
            List<Email> msgs = entry.getValue();
            // Sắp xếp thư trong luồng từ cũ đến mới
            msgs.sort(this::compareMailsChronological);
            Email latest = msgs.get(msgs.size() - 1);

            Email rep = JsonUtil.fromJson(JsonUtil.toJson(latest), Email.class);
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

            // Bất kỳ thư nào chưa đọc -> Luồng hiển thị chưa đọc
            boolean anyUnread = msgs.stream().anyMatch(m -> !m.isRead());
            rep.setRead(!anyUnread);

            // Bất kỳ thư nào gắn sao -> Luồng hiển thị sao
            boolean anyStarred = msgs.stream().anyMatch(Email::isStarred);
            rep.setStarred(anyStarred);

            // Bất kỳ thư nào có đính kèm -> Luồng hiển thị icon tệp
            if (msgs.stream().anyMatch(Email::hasAttachments)) {
                if (!rep.hasAttachments()) {
                    rep.setAttachments(List.of(new Attachment("placeholder", "file", 0, "OTHER", "", "")));
                }
            }

            threadList.add(rep);
        }

        // Sắp xếp luồng theo hoạt động mới nhất lên đầu
        threadList.sort(this::compareMailsNewestFirst);

        // Phân trang
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        int startIndex = (page - 1) * pageSize;
        if (startIndex >= threadList.size()) {
            return Collections.emptyList();
        }
        int endIndex = Math.min(startIndex + pageSize, threadList.size());
        return threadList.subList(startIndex, endIndex);
    }

    /**
     * Lấy toàn bộ các email thuộc về một chuỗi hội thoại (Conversation Thread)
     */
    public synchronized List<Email> getThread(String username, String threadId) {
        if (username == null || threadId == null) return Collections.emptyList();
        Map<String, Email> uniqueMails = new HashMap<>();

        for (MailFolder folder : MailFolder.values()) {
            if (folder == MailFolder.TRASH) continue;
            List<Email> folderMails = loadFolderMails(username, folder);
            for (Email m : folderMails) {
                if (threadId.equalsIgnoreCase(m.getThreadId())
                        || threadId.equalsIgnoreCase(m.getId())
                        || threadId.equalsIgnoreCase(m.getReplyToId())
                        || threadId.equalsIgnoreCase(Email.normalizeSubject(m.getSubject()).toLowerCase())
                        || threadId.equalsIgnoreCase(Email.normalizeSubject(m.getSubject()))) {
                    uniqueMails.put(m.getId(), m);
                }
            }
        }

        // Nếu không thấy trong các thư mục chính, tìm kiếm trong Thùng rác
        if (uniqueMails.isEmpty()) {
            List<Email> trashMails = loadFolderMails(username, MailFolder.TRASH);
            for (Email m : trashMails) {
                if (threadId.equalsIgnoreCase(m.getThreadId())
                        || threadId.equalsIgnoreCase(m.getId())
                        || threadId.equalsIgnoreCase(m.getReplyToId())
                        || threadId.equalsIgnoreCase(Email.normalizeSubject(m.getSubject()).toLowerCase())
                        || threadId.equalsIgnoreCase(Email.normalizeSubject(m.getSubject()))) {
                    uniqueMails.put(m.getId(), m);
                }
            }
        }

        List<Email> threadMessages = new ArrayList<>(uniqueMails.values());
        // Sắp xếp từ cũ nhất đến mới nhất theo dòng thời gian hội thoại
        threadMessages.sort(this::compareMailsChronological);

        // Đánh dấu tất cả thư trong thread là đã đọc
        for (Email m : threadMessages) {
            if (!m.isRead()) {
                m.setRead(true);
                markRead(username, m.getId(), true);
            }
        }

        return threadMessages;
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
            try {
                return Files.deleteIfExists(inTrashPath);
            } catch (IOException e) {
                return false;
            }
        }

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
        draft.setTimestamp(Math.max(draft.getTimestamp(), SEQUENCE.incrementAndGet()));
        if (draft.getThreadId() == null || draft.getThreadId().isEmpty()) {
            draft.setThreadId(draft.getId());
        }
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

    private Email findMailAnywhere(String mailId) {
        if (mailId == null) return null;
        try {
            if (Files.exists(mailboxRoot)) {
                try (var userStream = Files.list(mailboxRoot)) {
                    for (Path userDir : userStream.toList()) {
                        if (Files.isDirectory(userDir)) {
                            for (MailFolder f : MailFolder.values()) {
                                Path file = userDir.resolve(f.name().toLowerCase()).resolve(mailId + ".json");
                                if (Files.exists(file)) {
                                    return loadEmailFromFile(file);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
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
