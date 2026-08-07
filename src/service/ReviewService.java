package service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reviews: a customer may review a past performance they attended
 * (held a non-cancelled ticket), at most once per performance.
 */
public class ReviewService {

    private final Connection conn;
    private final BookingService booking;

    public ReviewService(Connection conn) {
        this.conn = conn;
        this.booking = new BookingService(conn);
    }

    public int addReview(
            int customerId,
            int performanceId,
            int eventRating,
            int venueRating,
            String comment)
            throws SQLException {
        booking.requireCustomer(customerId);

        if (eventRating < 1 || eventRating > 5 || venueRating < 1 || venueRating > 5) {
            throw new SQLException("Ratings must be integers from 1 to 5.");
        }
        if (comment == null || comment.isBlank()) {
            throw new SQLException("Comment text is required.");
        }

        // Performance must be in the past and not cancelled.
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT cancelled, p_datetime < NOW() AS past"
                                + " FROM Performance WHERE performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Performance not found.");
                }
                if (rs.getBoolean("cancelled")) {
                    throw new SQLException("Cannot review a cancelled performance.");
                }
                if (!rs.getBoolean("past")) {
                    throw new SQLException("You can only review performances that have taken place.");
                }
            }
        }

        // Customer must have held a non-cancelled ticket (as original buyer or resale buyer).
        if (!attended(customerId, performanceId)) {
            throw new SQLException(
                    "You can only review a performance you attended (held a non-cancelled ticket).");
        }

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO Review"
                                + " (event_rating, venue_rating, comment_text, created_at,"
                                + "  user_id, performance_id)"
                                + " VALUES (?,?,?,NOW(),?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, eventRating);
            ps.setInt(2, venueRating);
            ps.setString(3, comment);
            ps.setInt(4, customerId);
            ps.setInt(5, performanceId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private boolean attended(int customerId, int performanceId) throws SQLException {
        // Original buyer of a non-cancelled ticket for this performance,
        // or a resale buyer of such a ticket.
        String sql =
                "SELECT t.ticket_id,"
                        + " o.user_id AS original_buyer,"
                        + " (SELECT r.user_id FROM Resale r"
                        + "  JOIN Listing l ON l.listing_id = r.listing_id"
                        + "  WHERE l.ticket_id = t.ticket_id"
                        + "  ORDER BY r.sold_at DESC LIMIT 1) AS last_buyer"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " WHERE o.performance_id = ? AND t.cancelled = FALSE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int last = rs.getInt("last_buyer");
                    int owner = rs.wasNull() ? rs.getInt("original_buyer") : last;
                    // Also allow anyone who held it at any point: original buyer or any resale buyer
                    if (owner == customerId || rs.getInt("original_buyer") == customerId) {
                        return true;
                    }
                    if (wasResaleBuyer(customerId, rs.getInt("ticket_id"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean wasResaleBuyer(int customerId, int ticketId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM Resale r"
                                + " JOIN Listing l ON l.listing_id = r.listing_id"
                                + " WHERE l.ticket_id = ? AND r.user_id = ?")) {
            ps.setInt(1, ticketId);
            ps.setInt(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
