package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** JDBC connection factory for MyTix. */
public final class Database {

    public static final String URL =
            "jdbc:mysql://localhost:3306/ticketMaster_db"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    public static final String USER = "root";
    public static final String PASSWORD = "";

    private Database() {}

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
