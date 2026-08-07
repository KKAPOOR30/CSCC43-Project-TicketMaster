package service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

/** Create / delete users (customers and organizers). */
public class UserService {

    private final Connection conn;

    public UserService(Connection conn) {
        this.conn = conn;
    }

    public int createUser(
            String name,
            String address,
            String email,
            LocalDate dob,
            boolean asCustomer,
            boolean asOrganizer) throws SQLException {
        if (!asCustomer && !asOrganizer) {
            throw new IllegalArgumentException("Select at least one role: customer or organizer.");
        }

        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int userId;
            String insertUser =
                    "INSERT INTO `User` (name, address, email, date_of_birth) VALUES (?,?,?,?)";
            try (PreparedStatement ps =
                    conn.prepareStatement(insertUser, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, address);
                ps.setString(3, email);
                ps.setDate(4, Date.valueOf(dob));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("User insert returned no id.");
                    }
                    userId = keys.getInt(1);
                }
            }

            if (asCustomer) {
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO Customer (user_id) VALUES (?)")) {
                    ps.setInt(1, userId);
                    ps.executeUpdate();
                }
            }
            if (asOrganizer) {
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO Organizer (user_id) VALUES (?)")) {
                    ps.setInt(1, userId);
                    ps.executeUpdate();
                }
            }

            conn.commit();
            return userId;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    public void deleteUser(int userId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("DELETE FROM `User` WHERE user_id = ?")) {
            ps.setInt(1, userId);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new SQLException("No user with id " + userId);
            }
        }
    }

    public boolean isCustomer(int userId) throws SQLException {
        return exists("SELECT 1 FROM Customer WHERE user_id = ?", userId);
    }

    public boolean isOrganizer(int userId) throws SQLException {
        return exists("SELECT 1 FROM Organizer WHERE user_id = ?", userId);
    }

    public String getName(int userId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT name FROM `User` WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    public void printUser(int userId) throws SQLException {
        String sql =
                "SELECT u.user_id, u.name, u.email, u.date_of_birth, u.address,"
                        + " EXISTS(SELECT 1 FROM Customer c WHERE c.user_id = u.user_id) AS is_customer,"
                        + " EXISTS(SELECT 1 FROM Organizer o WHERE o.user_id = u.user_id) AS is_organizer"
                        + " FROM `User` u WHERE u.user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("  User not found.");
                    return;
                }
                System.out.printf(
                        "  #%d  %s  <%s>%n  DOB %s%n  %s%n  roles: %s%s%n",
                        rs.getInt("user_id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getDate("date_of_birth"),
                        rs.getString("address"),
                        rs.getBoolean("is_customer") ? "customer " : "",
                        rs.getBoolean("is_organizer") ? "organizer" : "");
            }
        }
    }

    private boolean exists(String sql, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
