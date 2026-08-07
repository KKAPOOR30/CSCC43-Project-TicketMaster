package service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Organizer operations: events, performances, tiers, blocking, cancellation. */
public class OrganizerService {

    private final Connection conn;

    public OrganizerService(Connection conn) {
        this.conn = conn;
    }

    public void requireOrganizer(int userId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT 1 FROM Organizer WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("User " + userId + " is not an organizer.");
                }
            }
        }
    }

    public void requireOwnsEvent(int organizerId, int eventId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM Event WHERE event_id = ? AND user_id = ?")) {
            ps.setInt(1, eventId);
            ps.setInt(2, organizerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Event " + eventId + " is not managed by organizer " + organizerId);
                }
            }
        }
    }

    public int createEvent(
            int organizerId, String name, String genre, BigDecimal resaleCapPct)
            throws SQLException {
        requireOrganizer(organizerId);
        String sql =
                "INSERT INTO Event (name, genre, resale_cap_pct, user_id) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, genre);
            ps.setBigDecimal(3, resaleCapPct);
            ps.setInt(4, organizerId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Event insert returned no id.");
                }
                return keys.getInt(1);
            }
        }
    }

    public void setResaleCap(int organizerId, int eventId, BigDecimal resaleCapPct)
            throws SQLException {
        requireOwnsEvent(organizerId, eventId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "UPDATE Event SET resale_cap_pct = ? WHERE event_id = ?")) {
            ps.setBigDecimal(1, resaleCapPct);
            ps.setInt(2, eventId);
            ps.executeUpdate();
        }
    }

    public void addArtistToEvent(
            int organizerId, int eventId, int artistId, String billingOrder)
            throws SQLException {
        requireOwnsEvent(organizerId, eventId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO Features (event_id, artist_id, billing_order)"
                                + " VALUES (?,?,?)")) {
            ps.setInt(1, eventId);
            ps.setInt(2, artistId);
            ps.setString(3, billingOrder);
            ps.executeUpdate();
        }
    }

    public int addPerformance(
            int organizerId, int eventId, int venueId, LocalDateTime when)
            throws SQLException {
        requireOwnsEvent(organizerId, eventId);
        String sql =
                "INSERT INTO Performance (p_datetime, cancelled, cancelled_at, event_id, venue_id)"
                        + " VALUES (?, FALSE, NULL, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, Timestamp.valueOf(when));
            ps.setInt(2, eventId);
            ps.setInt(3, venueId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Performance insert returned no id.");
                }
                return keys.getInt(1);
            }
        }
    }

    public void addPriceTier(int organizerId, int performanceId, String tierName, BigDecimal price)
            throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO PriceTier (performance_id, tier_name, price)"
                                + " VALUES (?,?,?)")) {
            ps.setInt(1, performanceId);
            ps.setString(2, tierName);
            ps.setBigDecimal(3, price);
            ps.executeUpdate();
        }
    }

    public void assignSectionToTier(
            int organizerId,
            int performanceId,
            int venueId,
            String sectionName,
            String tierName)
            throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO AssignedTo"
                                + " (venue_id, section_name, performance_id, tier_name)"
                                + " VALUES (?,?,?,?)")) {
            ps.setInt(1, venueId);
            ps.setString(2, sectionName);
            ps.setInt(3, performanceId);
            ps.setString(4, tierName);
            ps.executeUpdate();
        }
    }

    /**
     * Updates a tier price only when the performance is in the future and no
     * non-cancelled ticket has been sold in that tier.
     */
    public void updateTierPrice(
            int organizerId, int performanceId, String tierName, BigDecimal newPrice)
            throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);

        if (!isFuturePerformance(performanceId)) {
            throw new SQLException("Only future performances can have their tier prices changed.");
        }

        String check =
                "SELECT COUNT(*) AS cnt FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " LEFT JOIN SeatTicket st ON st.ticket_id = t.ticket_id"
                        + " LEFT JOIN GATicket gt ON gt.ticket_id = t.ticket_id"
                        + " JOIN AssignedTo a ON a.performance_id = o.performance_id"
                        + "   AND a.tier_name = ?"
                        + "   AND (("
                        + "        st.ticket_id IS NOT NULL"
                        + "        AND a.venue_id = st.venue_id"
                        + "        AND a.section_name = st.section_name"
                        + "       ) OR ("
                        + "        gt.ticket_id IS NOT NULL"
                        + "        AND a.venue_id = gt.venue_id"
                        + "        AND a.section_name = gt.section_name"
                        + "       ))"
                        + " WHERE o.performance_id = ? AND t.cancelled = FALSE";

        try (PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, tierName);
            ps.setInt(2, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt("cnt") > 0) {
                    throw new SQLException(
                            "Cannot change price: tickets have already been sold in tier '"
                                    + tierName
                                    + "' for this performance.");
                }
            }
        }

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "UPDATE PriceTier SET price = ?"
                                + " WHERE performance_id = ? AND tier_name = ?")) {
            ps.setBigDecimal(1, newPrice);
            ps.setInt(2, performanceId);
            ps.setString(3, tierName);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new SQLException("Tier '" + tierName + "' not found on this performance.");
            }
        }
    }

    public void blockSeat(
            int organizerId,
            int performanceId,
            int venueId,
            String section,
            String row,
            int seatNumber,
            String reason)
            throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO Blocks"
                                + " (performance_id, venue_id, section_name, row_name,"
                                + "  seat_number, blocked_at, reason)"
                                + " VALUES (?,?,?,?,?,NOW(),?)")) {
            ps.setInt(1, performanceId);
            ps.setInt(2, venueId);
            ps.setString(3, section);
            ps.setString(4, row);
            ps.setInt(5, seatNumber);
            ps.setString(6, reason);
            ps.executeUpdate();
        }
    }

    public void unblockSeat(
            int organizerId,
            int performanceId,
            int venueId,
            String section,
            String row,
            int seatNumber)
            throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "DELETE FROM Blocks"
                                + " WHERE performance_id = ? AND venue_id = ?"
                                + "   AND section_name = ? AND row_name = ?"
                                + "   AND seat_number = ?")) {
            ps.setInt(1, performanceId);
            ps.setInt(2, venueId);
            ps.setString(3, section);
            ps.setString(4, row);
            ps.setInt(5, seatNumber);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new SQLException("That seat is not currently blocked for this performance.");
            }
        }
    }

    /** Cancels a performance and refunds every sold (non-cancelled) ticket. */
    public void cancelPerformance(int organizerId, int performanceId) throws SQLException {
        requireOwnsPerformance(organizerId, performanceId);

        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "UPDATE Performance"
                                    + " SET cancelled = TRUE, cancelled_at = NOW()"
                                    + " WHERE performance_id = ? AND cancelled = FALSE")) {
                ps.setInt(1, performanceId);
                if (ps.executeUpdate() == 0) {
                    throw new SQLException("Performance already cancelled or not found.");
                }
            }

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "UPDATE Ticket t"
                                    + " JOIN Orders o ON o.order_id = t.order_id"
                                    + " SET t.cancelled = TRUE,"
                                    + "     t.cancelled_at = NOW(),"
                                    + "     t.cancellation_reason = 'performance_cancelled'"
                                    + " WHERE o.performance_id = ? AND t.cancelled = FALSE")) {
                ps.setInt(1, performanceId);
                int refunded = ps.executeUpdate();
                System.out.println("  Refunded " + refunded + " ticket(s).");
            }

            // Withdraw any active resale listings on those tickets.
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "UPDATE Listing l"
                                    + " JOIN Ticket t ON t.ticket_id = l.ticket_id"
                                    + " JOIN Orders o ON o.order_id = t.order_id"
                                    + " SET l.status = 'withdrawn'"
                                    + " WHERE o.performance_id = ? AND l.status = 'active'")) {
                ps.setInt(1, performanceId);
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    public void listGenres() throws SQLException {
        try (PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT genre, segment FROM Genre ORDER BY segment, genre");
                ResultSet rs = ps.executeQuery()) {
            System.out.println("  Genre (Segment)");
            while (rs.next()) {
                System.out.printf("  - %s (%s)%n", rs.getString(1), rs.getString(2));
            }
        }
    }

    public void listVenues() throws SQLException {
        String sql =
                "SELECT v.venue_id, v.name, p.city, p.country"
                        + " FROM Venue v JOIN PostalCode p ON p.postal_code = v.postal_code"
                        + " ORDER BY v.venue_id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf(
                        "  #%d  %s — %s, %s%n",
                        rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    public void listSections(int venueId) throws SQLException {
        String sql =
                "SELECT s.section_name,"
                        + " CASE WHEN r.section_name IS NOT NULL THEN 'reserved'"
                        + "      WHEN g.section_name IS NOT NULL THEN CONCAT('GA cap=', g.capacity)"
                        + "      ELSE '?' END AS kind"
                        + " FROM Section s"
                        + " LEFT JOIN ReservedSection r"
                        + "   ON r.venue_id = s.venue_id AND r.section_name = s.section_name"
                        + " LEFT JOIN GASection g"
                        + "   ON g.venue_id = s.venue_id AND g.section_name = s.section_name"
                        + " WHERE s.venue_id = ?"
                        + " ORDER BY s.section_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, venueId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("  - %s [%s]%n", rs.getString(1), rs.getString(2));
                }
            }
        }
    }

    public void listArtists() throws SQLException {
        try (PreparedStatement ps =
                        conn.prepareStatement("SELECT artist_id, name FROM Artist ORDER BY artist_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf("  #%d  %s%n", rs.getInt(1), rs.getString(2));
            }
        }
    }

    public void listOrganizerEvents(int organizerId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT event_id, name, genre, resale_cap_pct"
                                + " FROM Event WHERE user_id = ? ORDER BY event_id")) {
            ps.setInt(1, organizerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf(
                            "  #%d  %s  [%s]  resale cap %.0f%%%n",
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getBigDecimal(4));
                }
            }
        }
    }

    public void listPerformances(int eventId) throws SQLException {
        String sql =
                "SELECT p.performance_id, p.p_datetime, v.name, p.cancelled"
                        + " FROM Performance p JOIN Venue v ON v.venue_id = p.venue_id"
                        + " WHERE p.event_id = ? ORDER BY p.p_datetime";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf(
                            "  #%d  %s  @ %s%s%n",
                            rs.getInt(1),
                            rs.getTimestamp(2),
                            rs.getString(3),
                            rs.getBoolean(4) ? "  [CANCELLED]" : "");
                }
            }
        }
    }

    private void requireOwnsPerformance(int organizerId, int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM Performance p"
                                + " JOIN Event e ON e.event_id = p.event_id"
                                + " WHERE p.performance_id = ? AND e.user_id = ?")) {
            ps.setInt(1, performanceId);
            ps.setInt(2, organizerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Performance "
                                    + performanceId
                                    + " is not managed by organizer "
                                    + organizerId);
                }
            }
        }
    }

    private boolean isFuturePerformance(int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT p_datetime > NOW() FROM Performance WHERE performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
