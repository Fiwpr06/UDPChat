package com.udpchat.server.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Quản lý cấu hình kết nối Gmail (SMTP & IMAP) từ tệp cấu hình runtime
 */
public class GmailConfig {
    private static final Path CONFIG_PATH = Paths.get("server-data", "gmail-config.properties");

    private boolean enabled = false;
    private String gmailAddress = "";
    private String appPassword = "";
    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 587;
    private String imapHost = "imap.gmail.com";
    private int imapPort = 993;
    private int imapPollIntervalSeconds = 60;

    public GmailConfig() {
        load();
    }

    public synchronized void load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                save(); // Tạo tệp mặc định nếu chưa tồn tại
                return;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            this.enabled = Boolean.parseBoolean(props.getProperty("gmail.enabled", "false"));
            this.gmailAddress = props.getProperty("gmail.address", "").trim();
            this.appPassword = props.getProperty("gmail.app.password", "").trim();
            this.smtpHost = props.getProperty("gmail.smtp.host", "smtp.gmail.com").trim();
            this.smtpPort = Integer.parseInt(props.getProperty("gmail.smtp.port", "587").trim());
            this.imapHost = props.getProperty("gmail.imap.host", "imap.gmail.com").trim();
            this.imapPort = Integer.parseInt(props.getProperty("gmail.imap.port", "993").trim());
            this.imapPollIntervalSeconds = Integer.parseInt(props.getProperty("gmail.imap.poll.interval.seconds", "60").trim());
        } catch (Exception e) {
            System.err.println("Không thể đọc tệp cấu hình Gmail: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (CONFIG_PATH.getParent() != null) {
                Files.createDirectories(CONFIG_PATH.getParent());
            }
            Properties props = new Properties();
            props.setProperty("gmail.enabled", String.valueOf(enabled));
            props.setProperty("gmail.address", gmailAddress != null ? gmailAddress : "");
            props.setProperty("gmail.app.password", appPassword != null ? appPassword : "");
            props.setProperty("gmail.smtp.host", smtpHost);
            props.setProperty("gmail.smtp.port", String.valueOf(smtpPort));
            props.setProperty("gmail.imap.host", imapHost);
            props.setProperty("gmail.imap.port", String.valueOf(imapPort));
            props.setProperty("gmail.imap.poll.interval.seconds", String.valueOf(imapPollIntervalSeconds));

            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "Gmail Integration Configuration");
            }
        } catch (Exception e) {
            System.err.println("Không thể lưu tệp cấu hình Gmail: " + e.getMessage());
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

    public int getImapPollIntervalSeconds() { return imapPollIntervalSeconds; }
    public void setImapPollIntervalSeconds(int interval) { this.imapPollIntervalSeconds = Math.max(10, interval); }
}
