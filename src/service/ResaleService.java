package service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Resale market: list, withdraw, purchase. */
public class ResaleService {

    private final Connection conn;
    private final BookingService booking;

    public ResaleService(Connection conn) {
        this.conn = conn;
        this.booking = new BookingService(conn);
    }

    public int currentOwner(int ticketId) throws SQLException {
        String sql =
                "SELECT o.user_id AS original_buyer,"
                        + " (SELECT r.user_id FROM Resale r"
                        + "  JOIN Listing l ON l.listing_id = r.listing_id"
                        + "  WHERE l.ticket_id = t.ticket_id"
                        + "  ORDER BY r.sold_at DESC LIMIT 1) AS last_buyer"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " WHERE t.ticket_id = ? AND t.cancelled = FALSE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Ticket not found or cancelled.");
                }
                int last = rs.getInt("last_buyer");
                if (!rs.wasNull()) {
                    return last;
                }
                return rs.getInt("original_buyer");
            }
        }
    }

    public int listForResale(int customerId, int ticketId, BigDecimal listPrice)
            throws SQLException {
        booking.requireCustomer(customerId);

        if (currentOwner(ticketId) != customerId) {
            throw new SQLException("You do not currently own this ticket.");
        }

        // No other active listing.
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM Listing WHERE ticket_id = ? AND status = 'active'")) {
            ps.setInt(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new SQLException("This ticket already has an active listing.");
                }
            }
        }

        // Cap check is also enforced by trigger; pre-check for a clearer message.
        BigDecimal cap = resaleCapPrice(ticketId);
        if (listPrice.compareTo(cap) > 0) {
            throw new SQLException(
                    "List price $" + listPrice + " exceeds resale cap $" + cap + ".");
        }

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO Listing"
                                + " (list_price, status, listed_at, ticket_id, user_id)"
                                + " VALUES (?,'active',NOW(),?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setBigDecimal(1, listPrice);
            ps.setInt(2, ticketId);
            ps.setInt(3, customerId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public void withdrawListing(int customerId, int listingId) throws SQLException {
        booking.requireCustomer(customerId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "UPDATE Listing SET status = 'withdrawn'"
                                + " WHERE listing_id = ? AND user_id = ? AND status = 'active'")) {
            ps.setInt(1, listingId);
            ps.setInt(2, customerId);
            if (ps.executeUpdate() == 0) {
                throw new SQLException(
                        "No active listing #" + listingId + " found for this customer.");
            }
        }
    }

    public int buyListing(int buyerId, int listingId) throws SQLException {
        booking.requireCustomer(buyerId);

        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int ticketId;
            int sellerId;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT listing_id, ticket_id, user_id, status"
                                    + " FROM Listing WHERE listing_id = ? FOR UPDATE")) {
                ps.setInt(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Listing not found.");
                    }
                    if (!"active".equals(rs.getString("status"))) {
                        throw new SQLException("Listing is not active.");
                    }
                    ticketId = rs.getInt("ticket_id");
                    sellerId = rs.getInt("user_id");
                }
            }

            if (sellerId == buyerId) {
                throw new SQLException("You cannot buy your own listing.");
            }
            if (currentOwner(ticketId) != sellerId) {
                throw new SQLException("Seller no longer owns this ticket.");
            }

            int resaleId;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO Resale (sold_at, listing_id, user_id)"
                                    + " VALUES (NOW(),?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, listingId);
                ps.setInt(2, buyerId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    resaleId = keys.getInt(1);
                }
            }

            conn.commit();
            System.out.println(
                    "  Ownership of ticket #"
                            + ticketId
                            + " transferred to user #"
                            + buyerId
                            + " (resale #"
                            + resaleId
                            + ").");
            return resaleId;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    public void listActiveListings() throws SQLException {
        String sql =
                "SELECT l.listing_id, l.list_price, l.listed_at, l.ticket_id, l.user_id,"
                        + " t.face_value, e.name, p.p_datetime"
                        + " FROM Listing l"
                        + " JOIN Ticket t ON t.ticket_id = l.ticket_id"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " WHERE l.status = 'active'"
                        + " ORDER BY l.listed_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf(
                        "  listing #%d  ticket #%d  $%s (face $%s)  seller #%d  \"%s\"  %s%n",
                        rs.getInt("listing_id"),
                        rs.getInt("ticket_id"),
                        rs.getBigDecimal("list_price"),
                        rs.getBigDecimal("face_value"),
                        rs.getInt("user_id"),
                        rs.getString("name"),
                        rs.getTimestamp("p_datetime"));
            }
        }
    }

    public void listMyListings(int customerId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT listing_id, list_price, status, listed_at, ticket_id"
                                + " FROM Listing WHERE user_id = ? ORDER BY listed_at DESC")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf(
                            "  listing #%d  ticket #%d  $%s  [%s]  %s%n",
                            rs.getInt(1),
                            rs.getInt(5),
                            rs.getBigDecimal(2),
                            rs.getString(3),
                            rs.getTimestamp(4));
                }
            }
        }
    }

    private BigDecimal resaleCapPrice(int ticketId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT t.face_value * e.resale_cap_pct / 100 AS cap"
                                + " FROM Ticket t"
                                + " JOIN Orders o ON o.order_id = t.order_id"
                                + " JOIN Performance p ON p.performance_id = o.performance_id"
                                + " JOIN Event e ON e.event_id = p.event_id"
                                + " WHERE t.ticket_id = ?")) {
            ps.setInt(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal("cap");
            }
        }
    }
}
