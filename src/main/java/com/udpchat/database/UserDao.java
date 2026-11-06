package com.udpchat.database;

import com.udpchat.common.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Thao tác cơ sở dữ liệu cho Tài khoản người dùng (User)
 */
public class UserDao {

    public boolean register(String username, String password, String email) {
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return false;
        }
        String sql = "INSERT INTO users (username, password, email) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim().toLowerCase());
            stmt.setString(2, password);
            stmt.setString(3, email != null ? email.trim() : "");
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[UserDao] Lỗi đăng ký: " + e.getMessage());
            return false;
        }
    }

    public User authenticate(String username, String password) {
        if (username == null || password == null) return null;
        String sql = "SELECT id, username, password, email FROM users WHERE username = ? AND password = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim().toLowerCase());
            stmt.setString(2, password);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new User(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("password"),
                            rs.getString("email")
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserDao] Lỗi xác thực: " + e.getMessage());
        }
        return null;
    }

    public User findByUsername(String username) {
        if (username == null) return null;
        String sql = "SELECT id, username, password, email FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim().toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new User(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("password"),
                            rs.getString("email")
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserDao] Lỗi tìm người dùng: " + e.getMessage());
        }
        return null;
    }

    public boolean existsByUsername(String username) {
        return findByUsername(username) != null;
    }
}
