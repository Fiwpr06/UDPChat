package com.udpchat.database;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Quản lý kết nối Cơ sở dữ liệu theo cơ chế Dual Database:
 * 1. Ưu tiên kết nối MySQL/MariaDB tại localhost:3306.
 * 2. Nếu MySQL chưa khởi chạy hoặc lỗi, tự động Fallback sang SQLite cục bộ (server-data/mail_server.db).
 */
public class DatabaseManager {
    public enum DatabaseType { MYSQL, SQLITE }

    private static DatabaseType currentType = DatabaseType.MYSQL;
    private static String mysqlHost = "localhost";
    private static int mysqlPort = 3306;
    private static String mysqlDb = "mail_server_db";
    private static String mysqlUser = "root";
    private static String mysqlPass = "";

    private static final String SQLITE_DIR = "server-data";
    private static final String SQLITE_FILE = "server-data/mail_server.db";
    private static final String SQLITE_URL = "jdbc:sqlite:" + SQLITE_FILE;

    private static boolean initialized = false;

    public static synchronized void initialize() {
        try {
            Files.createDirectories(Paths.get(SQLITE_DIR, "attachments"));
        } catch (Exception ignored) {}

        // 1. Thử kết nối MySQL trước
        System.out.println("[DatabaseManager] Đang kiểm tra kết nối MySQL tại " + mysqlHost + ":" + mysqlPort + "...");
        try {
            // Đảm bảo Database tồn tại nếu MySQL đang chạy
            String adminUrl = "jdbc:mariadb://" + mysqlHost + ":" + mysqlPort + "/?useUnicode=true&characterEncoding=UTF-8&connectTimeout=3000";
            try (Connection testConn = DriverManager.getConnection(adminUrl, mysqlUser, mysqlPass);
                 Statement stmt = testConn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + mysqlDb + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;");
            }

            // Kết nối vào database chính
            String dbUrl = "jdbc:mariadb://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDb + "?useUnicode=true&characterEncoding=UTF-8&connectTimeout=3000";
            try (Connection conn = DriverManager.getConnection(dbUrl, mysqlUser, mysqlPass)) {
                if (conn.isValid(2)) {
                    currentType = DatabaseType.MYSQL;
                    System.out.println("[DatabaseManager] ✓ Đã kết nối thành công tới MySQL (" + mysqlDb + ").");
                    initTables(conn);
                    initialized = true;
                    return;
                }
            }
        } catch (Exception e) {
            System.out.println("[DatabaseManager] ⚠️ Không thể kết nối MySQL (" + e.getMessage() + ").");
        }

        // 2. Fallback sang SQLite nhúng
        System.out.println("[DatabaseManager] 🔄 Đang tự động chuyển sang CSDL SQLite cục bộ: " + SQLITE_FILE);
        currentType = DatabaseType.SQLITE;
        try (Connection conn = DriverManager.getConnection(SQLITE_URL)) {
            initTables(conn);
            initialized = true;
            System.out.println("[DatabaseManager] ✓ Đã khởi tạo CSDL SQLite cục bộ thành công.");
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] ✗ Lỗi khởi tạo SQLite: " + e.getMessage());
        }
    }

    private static void initTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (currentType == DatabaseType.MYSQL) {
                // Kiểm tra xem bảng users cũ có thiếu cột password không để dọn dẹp bảng cũ
                try {
                    java.sql.DatabaseMetaData meta = conn.getMetaData();
                    try (java.sql.ResultSet rs = meta.getColumns(null, null, "users", "password")) {
                        boolean hasUsersTable = false;
                        try (java.sql.ResultSet rsTable = meta.getTables(null, null, "users", null)) {
                            if (rsTable.next()) hasUsersTable = true;
                        }
                        if (hasUsersTable && !rs.next()) {
                            stmt.executeUpdate("DROP TABLE IF EXISTS attachments;");
                            stmt.executeUpdate("DROP TABLE IF EXISTS email_recipients;");
                            stmt.executeUpdate("DROP TABLE IF EXISTS oauth_tokens;");
                            stmt.executeUpdate("DROP TABLE IF EXISTS server_config;");
                            stmt.executeUpdate("DROP TABLE IF EXISTS emails;");
                            stmt.executeUpdate("DROP TABLE IF EXISTS users;");
                        }
                    }
                } catch (Exception ignored) {}

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(50) NOT NULL UNIQUE,
                        password VARCHAR(100) NOT NULL,
                        email VARCHAR(100)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS emails (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        sender VARCHAR(100) NOT NULL,
                        recipient VARCHAR(100) NOT NULL,
                        subject VARCHAR(255) DEFAULT '(Không có chủ đề)',
                        body LONGTEXT,
                        sent_at VARCHAR(50),
                        is_read BOOLEAN DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        email_id INT NOT NULL,
                        filename VARCHAR(255) NOT NULL,
                        file_size BIGINT DEFAULT 0,
                        file_path VARCHAR(500),
                        FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);
            } else {
                // Cú pháp SQLite
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        password TEXT NOT NULL,
                        email TEXT
                    );
                """);

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS emails (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sender TEXT NOT NULL,
                        recipient TEXT NOT NULL,
                        subject TEXT DEFAULT '(Không có chủ đề)',
                        body TEXT,
                        sent_at TEXT,
                        is_read INTEGER DEFAULT 0
                    );
                """);

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        email_id INTEGER NOT NULL,
                        filename TEXT NOT NULL,
                        file_size INTEGER DEFAULT 0,
                        file_path TEXT,
                        FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
                    );
                """);
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        if (!initialized) {
            initialize();
        }
        if (currentType == DatabaseType.MYSQL) {
            String dbUrl = "jdbc:mariadb://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDb + "?useUnicode=true&characterEncoding=UTF-8";
            return DriverManager.getConnection(dbUrl, mysqlUser, mysqlPass);
        } else {
            return DriverManager.getConnection(SQLITE_URL);
        }
    }

    public static DatabaseType getCurrentType() {
        return currentType;
    }

    public static String getDatabaseDescription() {
        if (currentType == DatabaseType.MYSQL) {
            return "MySQL (" + mysqlHost + ":" + mysqlPort + "/" + mysqlDb + ")";
        } else {
            return "SQLite (" + SQLITE_FILE + ")";
        }
    }
}
