package com.udpchat.server.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Quản lý kết nối cơ sở dữ liệu MySQL / MariaDB thông qua HikariCP Connection Pool
 */
public class DatabaseManager {
    private static HikariDataSource dataSource;
    private static String currentHost = "localhost";
    private static int currentPort = 3306;
    private static String currentDb = "udp_mail_chat";
    private static String currentUser = "root";
    private static String currentPass = "";

    public static synchronized void initialize() throws SQLException {
        initialize(currentHost, currentPort, currentDb, currentUser, currentPass);
    }

    public static synchronized void initialize(String host, int port, String dbName, String user, String password) throws SQLException {
        currentHost = host;
        currentPort = port;
        currentDb = dbName;
        currentUser = user;
        currentPass = password != null ? password : "";

        // Đảm bảo Database tồn tại trước khi kết nối Pool
        ensureDatabaseExists(host, port, dbName, user, currentPass);

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        HikariConfig config = new HikariConfig();
        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + dbName
                + "?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true";
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(currentPass);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(6000);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        config.setPoolName("UDPChat-DBPool");

        dataSource = new HikariDataSource(config);

        // Khởi tạo các bảng từ file init.sql
        initializeSchema();
    }

    private static void ensureDatabaseExists(String host, int port, String dbName, String user, String password) {
        String adminUrl = "jdbc:mariadb://" + host + ":" + port + "/?useUnicode=true&characterEncoding=UTF-8";
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;");
        } catch (Exception e) {
            System.err.println("[DatabaseManager] Lưu ý khi tạo CSDL: " + e.getMessage());
        }
    }

    public static synchronized void initializeSchema() {
        try (Connection conn = getConnection();
             InputStream is = DatabaseManager.class.getResourceAsStream("/com/udpchat/server/init.sql")) {
            if (is == null) {
                System.err.println("[DatabaseManager] Không tìm thấy tệp /com/udpchat/server/init.sql");
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                 Statement stmt = conn.createStatement()) {
                StringBuilder sql = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("--")) continue;
                    sql.append(line).append(" ");
                    if (line.endsWith(";")) {
                        stmt.execute(sql.toString());
                        sql.setLength(0);
                    }
                }
                if (sql.length() > 0) {
                    stmt.execute(sql.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("[DatabaseManager] Lỗi khi tạo bảng: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            initialize();
        }
        return dataSource.getConnection();
    }

    public static synchronized boolean isConnected() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }

    public static String getCurrentHost() { return currentHost; }
    public static int getCurrentPort() { return currentPort; }
    public static String getCurrentDb() { return currentDb; }
    public static String getCurrentUser() { return currentUser; }
}
