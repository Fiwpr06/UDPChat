package com.udpchat.shared.model;

import com.udpchat.shared.protocol.UDPConstants;

public class Message {
    private String ip;
    private String senderName;
    private String timestamp;
    private String content;

    public Message(String ip, String senderName, String timestamp, String content) {
        this.ip = ip;
        this.senderName = senderName;
        this.timestamp = timestamp;
        this.content = content;
    }

    public String getIp() {
        return ip;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getContent() {
        return content;
    }

    public String toStorageString() {
        return ip + UDPConstants.DELIMITER + 
               senderName + UDPConstants.DELIMITER + 
               timestamp + UDPConstants.DELIMITER + 
               content;
    }

    public static Message fromStorageString(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split("\\" + UDPConstants.DELIMITER, 4);
        if (parts.length < 4) {
            return null; // Invalid format
        }
        return new Message(parts[0], parts[1], parts[2], parts[3]);
    }

    public String toDisplayString() {
        return "[" + timestamp + "] " + senderName + ": " + content;
    }
}
