package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JDBC connection factory for MyTix.
 *
 * Credentials default to the course-standard local MySQL setup (root, no
 * password) and can be overridden with the MYTIX_DB_URL, MYTIX_DB_USER, and
 * MYTIX_DB_PASSWORD environment variables without editing any source file.
 */
public final class Database {

    public static final String URL = env("MYTIX_DB_URL",
            "jdbc:mysql://localhost:3306/ticketMaster_db"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    public static final String USER = env("MYTIX_DB_USER", "root");
    public static final String PASSWORD = env("MYTIX_DB_PASSWORD", "");

    private Database() {}

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null) ? fallback : value;
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
