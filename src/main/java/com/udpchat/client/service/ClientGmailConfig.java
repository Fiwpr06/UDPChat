package com.udpchat.client.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Quản lý cấu hình kết nối Gmail trực tiếp của Client (lưu trữ cục bộ cho từng người dùng)
 */
public class ClientGmailConfig {
    private final Path cacheDir = Paths.get("client-cache");
    private String currentUsername = "default";

    private boolean enabled = false;
    private String gmailAddress = "";
    private String appPassword = "";
    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 587;
    private String imapHost = "imap.gmail.com";
    private int imapPort = 993;

    public ClientGmailConfig() {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {}
    }

    private Path getConfigPath(String username) {
        String safeName = (username != null && !username.trim().isEmpty())
                ? username.trim().replaceAll("[^a-zA-Z0-9_.-]", "_")
                : "default";
        return cacheDir.resolve("gmail-config-" + safeName + ".properties");
    }

    public synchronized void load(String username) {
        this.currentUsername = username;
        Path path = getConfigPath(username);
        if (!Files.exists(path)) {
            // Đặt lại mặc định
            this.enabled = false;
            this.gmailAddress = "";
            this.appPassword = "";
            return;
        }

        try (InputStream in = Files.newInputStream(path)) {
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            this.enabled = Boolean.parseBoolean(props.getProperty("gmail.enabled", "false"));
            this.gmailAddress = props.getProperty("gmail.address", "").trim();
            this.appPassword = props.getProperty("gmail.app.password", "").trim();
            this.smtpHost = props.getProperty("gmail.smtp.host", "smtp.gmail.com").trim();
            this.smtpPort = Integer.parseInt(props.getProperty("gmail.smtp.port", "587").trim());
            this.imapHost = props.getProperty("gmail.imap.host", "imap.gmail.com").trim();
            this.imapPort = Integer.parseInt(props.getProperty("gmail.imap.port", "993").trim());
        } catch (Exception e) {
            System.err.println("Không thể đọc cấu hình Gmail Client: " + e.getMessage());
        }
    }

    public synchronized void save(String username) {
        this.currentUsername = username;
        Path path = getConfigPath(username);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Properties props = new Properties();
            props.setProperty("gmail.enabled", String.valueOf(enabled));
            props.setProperty("gmail.address", gmailAddress != null ? gmailAddress : "");
            props.setProperty("gmail.app.password", appPassword != null ? appPassword : "");
            props.setProperty("gmail.smtp.host", smtpHost);
            props.setProperty("gmail.smtp.port", String.valueOf(smtpPort));
            props.setProperty("gmail.imap.host", imapHost);
            props.setProperty("gmail.imap.port", String.valueOf(imapPort));

            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "Client Direct Gmail Configuration");
            }
        } catch (Exception e) {
            System.err.println("Không thể lưu cấu hình Gmail Client: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return gmailAddress != null && !gmailAddress.trim().isEmpty()
                && appPassword != null && !appPassword.trim().isEmpty();
    }

    // Getters and Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getGmailAddress() { return gmailAddress; }
    public void setGmailAddress(String gmailAddress) { this.gmailAddress = gmailAddress != null ? gmailAddress.trim() : ""; }

    public String getAppPassword() { return appPassword; }
    public void setAppPassword(String appPassword) { this.appPassword = appPassword != null ? appPassword.trim() : ""; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getImapHost() { return imapHost; }
    public void setImapHost(String imapHost) { this.imapHost = imapHost; }

    public int getImapPort() { return imapPort; }
    public void setImapPort(int imapPort) { this.imapPort = imapPort; }
}
