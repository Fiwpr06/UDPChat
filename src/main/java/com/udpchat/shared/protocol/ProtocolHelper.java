package com.udpchat.shared.protocol;

import java.util.Arrays;

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
}
