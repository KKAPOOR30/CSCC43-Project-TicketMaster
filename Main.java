import java.sql.*;

public class Main {

    // ── Database credentials ──────────────────────────────────────────────────
    static final String URL      = "jdbc:mysql://localhost:3306/ticketMaster_db";
    static final String USER     = "root";
    static final String PASSWORD = "";
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("Connected to database.");

            // Create a user
            int newId = createUser(conn, "alice", "alice@example.com");
            System.out.println("Created user with id: " + newId);

            // Read it back
            readUser(conn, newId);
        }
    }

    static int createUser(Connection conn, String username, String email) throws SQLException {
        String sql = "INSERT INTO users (username, email) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        }
        throw new SQLException("Insert failed, no ID returned.");
    }

    static void readUser(Connection conn, int id) throws SQLException {
        String sql = "SELECT id, username, email FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.printf("User — id: %d, username: %s, email: %s%n",
                        rs.getInt("id"), rs.getString("username"), rs.getString("email"));
                }
            }
        }
    }
}
