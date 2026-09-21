package com.udpchat.server.service;

import com.udpchat.shared.model.Message;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MessageService {
    private final Path messagesDir;
    private final Path logFile;

    public MessageService() {
        messagesDir = Paths.get("server-data", "messages");
        logFile = messagesDir.resolve("messages.log");
        try {
            Files.createDirectories(messagesDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Lưu tin nhắn
    public synchronized void saveMessage(String ip, String senderName, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Message msg = new Message(ip, senderName, timestamp, content);
        try {
            Files.writeString(logFile, msg.toStorageString() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
