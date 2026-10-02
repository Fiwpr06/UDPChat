package com.udpchat.shared.protocol;

import com.udpchat.shared.model.Email;
import com.udpchat.shared.model.MailFolder;
import com.udpchat.shared.util.JsonUtil;

import java.util.Arrays;

/**
 * Tiện ích đóng gói và phân tích cú pháp gói tin giao thức UDP Mail
 */
public class ProtocolHelper {
    
    public static String buildRequest(Command cmd, String... params) {
        if (cmd == null) return "";
        StringBuilder sb = new StringBuilder(cmd.name());
        for (String param : params) {
            sb.append(UDPConstants.DELIMITER).append(param != null ? param : "");
        }
        return sb.toString();
    }
    
    public static String buildResponse(String status, String... data) {
        StringBuilder sb = new StringBuilder(Command.RESPONSE.name());
        sb.append(UDPConstants.DELIMITER).append(status != null ? status : "");
        for (String d : data) {
            sb.append(UDPConstants.DELIMITER).append(d != null ? d : "");
        }
        return sb.toString();
    }
    
    public static String buildPush(Command cmd, String... data) {
        if (cmd == null) return "";
        StringBuilder sb = new StringBuilder(cmd.name());
        for (String d : data) {
            sb.append(UDPConstants.DELIMITER).append(d != null ? d : "");
        }
        return sb.toString();
    }
    
    public static Command parseCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\\" + UDPConstants.DELIMITER);
        String cmdStr = parts[0];
        try {
            return Command.valueOf(cmdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public static String[] parseParams(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = raw.split("\\" + UDPConstants.DELIMITER, -1);
        if (parts.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(parts, 1, parts.length);
    }

    // Helper methods cho Mail Protocol
    public static String buildSendMailRequest(Email email) {
        String json = JsonUtil.toCompactJson(email);
        return buildRequest(Command.SEND_MAIL, JsonUtil.encodeBase64(json));
    }

    public static String buildListMailRequest(MailFolder folder, int page, int pageSize) {
        return buildRequest(Command.LIST_MAIL, folder != null ? folder.name() : MailFolder.INBOX.name(),
                String.valueOf(page), String.valueOf(pageSize));
    }

    public static String buildReadMailRequest(String mailId) {
        return buildRequest(Command.READ_MAIL, mailId);
    }

    public static String buildDeleteMailRequest(String mailId, boolean permanent) {
        return buildRequest(Command.DELETE_MAIL, mailId, String.valueOf(permanent));
    }

    public static String buildStarMailRequest(String mailId, boolean starred) {
        return buildRequest(Command.STAR_MAIL, mailId, String.valueOf(starred));
    }

    public static String buildMarkReadRequest(String mailId, boolean read) {
        return buildRequest(Command.MARK_READ, mailId, String.valueOf(read));
    }

    public static String buildSaveDraftRequest(Email email) {
        String json = JsonUtil.toCompactJson(email);
        return buildRequest(Command.SAVE_DRAFT, JsonUtil.encodeBase64(json));
    }

    public static String buildSearchMailRequest(String keyword) {
        return buildRequest(Command.SEARCH_MAIL, JsonUtil.encodeBase64(keyword));
    }

    public static String buildGetStatsRequest() {
        return buildRequest(Command.GET_STATS);
    }
}
