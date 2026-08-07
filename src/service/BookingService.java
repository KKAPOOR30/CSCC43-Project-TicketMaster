package service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Customer booking and ticket cancellation. */
public class BookingService {

    private final Connection conn;

    public BookingService(Connection conn) {
        this.conn = conn;
    }

    public void requireCustomer(int userId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT 1 FROM Customer WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("User " + userId + " is not a customer.");
                }
            }
        }
    }

    /**
     * Books reserved seats and/or GA tickets for a performance in one order.
     *
     * @param seats list of [section, row, seatNumber] triples (may be empty)
     * @param gaSection GA section name, or null
     * @param gaQty number of GA tickets (ignored if gaSection is null)
     */
    public int book(
            int customerId,
            int performanceId,
            String cardNumber,
            String cardExpiry,
            List<String[]> seats,
            String gaSection,
            int gaQty)
            throws SQLException {
        requireCustomer(customerId);

        if ((seats == null || seats.isEmpty()) && (gaSection == null || gaQty <= 0)) {
            throw new SQLException("Order must contain at least one ticket.");
        }

        ensurePerformanceBookable(performanceId);

        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int orderId;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO Orders"
                                    + " (order_date, card_number, card_expiry, user_id, performance_id)"
                                    + " VALUES (NOW(),?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, cardNumber);
                ps.setString(2, cardExpiry);
                ps.setInt(3, customerId);
                ps.setInt(4, performanceId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Order insert returned no id.");
                    }
                    orderId = keys.getInt(1);
                }
            }

            int venueId = venueOf(performanceId);
            int created = 0;

            if (seats != null) {
                for (String[] seat : seats) {
                    String section = seat[0];
                    String row = seat[1];
                    int seatNum = Integer.parseInt(seat[2]);
                    BigDecimal face = tierPrice(performanceId, venueId, section);
                    int ticketId = insertTicket(orderId, face);
                    insertSeatTicket(ticketId, venueId, section, row, seatNum);
                    created++;
                }
            }

            if (gaSection != null && gaQty > 0) {
                BigDecimal face = tierPrice(performanceId, venueId, gaSection);
                for (int i = 0; i < gaQty; i++) {
                    int ticketId = insertTicket(orderId, face);
                    insertGaTicket(ticketId, venueId, gaSection);
                    created++;
                }
            }

            conn.commit();
            System.out.println("  Created " + created + " ticket(s) on order #" + orderId);
            return orderId;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    /**
     * Cancels selected tickets on an order owned by the customer, only if the
     * performance is at least seven days away.
     */
    public void cancelTickets(int customerId, int orderId, List<Integer> ticketIds)
            throws SQLException {
        requireCustomer(customerId);

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT o.user_id, o.performance_id, p.p_datetime, p.cancelled"
                                + " FROM Orders o"
                                + " JOIN Performance p ON p.performance_id = o.performance_id"
                                + " WHERE o.order_id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Order not found.");
                }
                if (rs.getInt("user_id") != customerId) {
                    throw new SQLException("Only the customer who placed the order may cancel it.");
                }
                if (rs.getBoolean("cancelled")) {
                    throw new SQLException("Performance is already cancelled.");
                }
            }
        }

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT TIMESTAMPDIFF(DAY, NOW(), p.p_datetime) AS days_left"
                                + " FROM Orders o"
                                + " JOIN Performance p ON p.performance_id = o.performance_id"
                                + " WHERE o.order_id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt("days_left") < 7) {
                    throw new SQLException(
                            "Tickets may only be cancelled up to seven days before the performance.");
                }
            }
        }

        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int cancelled = 0;
            for (int ticketId : ticketIds) {
                try (PreparedStatement ps =
                        conn.prepareStatement(
                                "UPDATE Ticket"
                                        + " SET cancelled = TRUE,"
                                        + "     cancelled_at = NOW(),"
                                        + "     cancellation_reason = 'customer_cancelled'"
                                        + " WHERE ticket_id = ? AND order_id = ?"
                                        + "   AND cancelled = FALSE")) {
                    ps.setInt(1, ticketId);
                    ps.setInt(2, orderId);
                    cancelled += ps.executeUpdate();
                }
                // Withdraw any active listing for this ticket.
                try (PreparedStatement ps =
                        conn.prepareStatement(
                                "UPDATE Listing SET status = 'withdrawn'"
                                        + " WHERE ticket_id = ? AND status = 'active'")) {
                    ps.setInt(1, ticketId);
                    ps.executeUpdate();
                }
            }
            if (cancelled == 0) {
                throw new SQLException("No matching active tickets were cancelled.");
            }
            conn.commit();
            System.out.println("  Cancelled " + cancelled + " ticket(s); full refund recorded.");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    public void listCustomerOrders(int customerId) throws SQLException {
        String sql =
                "SELECT o.order_id, o.order_date, o.performance_id, p.p_datetime, e.name,"
                        + " (SELECT COUNT(*) FROM Ticket t WHERE t.order_id = o.order_id) AS tickets"
                        + " FROM Orders o"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " WHERE o.user_id = ?"
                        + " ORDER BY o.order_date DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf(
                            "  order #%d  %s  perf #%d  %s  \"%s\"  (%d tickets)%n",
                            rs.getInt("order_id"),
                            rs.getTimestamp("order_date"),
                            rs.getInt("performance_id"),
                            rs.getTimestamp("p_datetime"),
                            rs.getString("name"),
                            rs.getInt("tickets"));
                }
            }
        }
    }

    public void listOrderTickets(int customerId, int orderId) throws SQLException {
        String sql =
                "SELECT t.ticket_id, t.face_value, t.cancelled, t.cancellation_reason,"
                        + " st.section_name AS seat_sec, st.row_name, st.seat_number,"
                        + " gt.section_name AS ga_sec"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " LEFT JOIN SeatTicket st ON st.ticket_id = t.ticket_id"
                        + " LEFT JOIN GATicket gt ON gt.ticket_id = t.ticket_id"
                        + " WHERE t.order_id = ? AND o.user_id = ?"
                        + " ORDER BY t.ticket_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ps.setInt(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    String where;
                    if (rs.getString("seat_sec") != null) {
                        where =
                                rs.getString("seat_sec")
                                        + " / row "
                                        + rs.getString("row_name")
                                        + " / seat "
                                        + rs.getInt("seat_number");
                    } else {
                        where = "GA " + rs.getString("ga_sec");
                    }
                    System.out.printf(
                            "  ticket #%d  $%s  %s%s%n",
                            rs.getInt("ticket_id"),
                            rs.getBigDecimal("face_value"),
                            where,
                            rs.getBoolean("cancelled")
                                    ? "  [CANCELLED: " + rs.getString("cancellation_reason") + "]"
                                    : "");
                }
                if (!any) {
                    System.out.println("  No tickets (wrong order id or not your order).");
                }
            }
        }
    }

    /** Tickets the customer currently owns (original or via resale), not cancelled. */
    public void listOwnedTickets(int customerId) throws SQLException {
        String sql =
                "SELECT t.ticket_id, t.face_value, o.performance_id, p.p_datetime, e.name,"
                        + " st.section_name AS seat_sec, st.row_name, st.seat_number,"
                        + " gt.section_name AS ga_sec"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " LEFT JOIN SeatTicket st ON st.ticket_id = t.ticket_id"
                        + " LEFT JOIN GATicket gt ON gt.ticket_id = t.ticket_id"
                        + " WHERE t.cancelled = FALSE"
                        + "   AND COALESCE("
                        + "     (SELECT r.user_id FROM Resale r"
                        + "      JOIN Listing l ON l.listing_id = r.listing_id"
                        + "      WHERE l.ticket_id = t.ticket_id"
                        + "      ORDER BY r.sold_at DESC LIMIT 1),"
                        + "     o.user_id"
                        + "   ) = ?"
                        + " ORDER BY p.p_datetime, t.ticket_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String where =
                            rs.getString("seat_sec") != null
                                    ? rs.getString("seat_sec")
                                            + " "
                                            + rs.getString("row_name")
                                            + "-"
                                            + rs.getInt("seat_number")
                                    : "GA " + rs.getString("ga_sec");
                    System.out.printf(
                            "  ticket #%d  $%s  perf #%d  %s  \"%s\"  %s%n",
                            rs.getInt("ticket_id"),
                            rs.getBigDecimal("face_value"),
                            rs.getInt("performance_id"),
                            rs.getTimestamp("p_datetime"),
                            rs.getString("name"),
                            where);
                }
            }
        }
    }

    public List<Integer> parseTicketIdList(String csv) {
        List<Integer> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String part : csv.split(",")) {
            ids.add(Integer.parseInt(part.trim()));
        }
        return ids;
    }

    private void ensurePerformanceBookable(int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT cancelled, p_datetime > NOW() AS upcoming"
                                + " FROM Performance WHERE performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Performance not found.");
                }
                if (rs.getBoolean("cancelled")) {
                    throw new SQLException("Cannot book a cancelled performance.");
                }
                if (!rs.getBoolean("upcoming")) {
                    throw new SQLException("Cannot book a past performance.");
                }
            }
        }
    }

    private int venueOf(int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT venue_id FROM Performance WHERE performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private BigDecimal tierPrice(int performanceId, int venueId, String section)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT pt.price FROM AssignedTo a"
                                + " JOIN PriceTier pt ON pt.performance_id = a.performance_id"
                                + "   AND pt.tier_name = a.tier_name"
                                + " WHERE a.performance_id = ? AND a.venue_id = ?"
                                + "   AND a.section_name = ?")) {
            ps.setInt(1, performanceId);
            ps.setInt(2, venueId);
            ps.setString(3, section);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                            "Section '" + section + "' has no price tier for this performance.");
                }
                return rs.getBigDecimal(1);
            }
        }
    }

    private int insertTicket(int orderId, BigDecimal face) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO Ticket"
                                + " (face_value, cancelled, cancelled_at, cancellation_reason, order_id)"
                                + " VALUES (?, FALSE, NULL, NULL, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setBigDecimal(1, face);
            ps.setInt(2, orderId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private void insertSeatTicket(
            int ticketId, int venueId, String section, String row, int seatNum)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO SeatTicket"
                                + " (ticket_id, venue_id, section_name, row_name, seat_number)"
                                + " VALUES (?,?,?,?,?)")) {
            ps.setInt(1, ticketId);
            ps.setInt(2, venueId);
            ps.setString(3, section);
            ps.setString(4, row);
            ps.setInt(5, seatNum);
            ps.executeUpdate();
        }
    }

    private void insertGaTicket(int ticketId, int venueId, String section) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO GATicket (ticket_id, venue_id, section_name)"
                                + " VALUES (?,?,?)")) {
            ps.setInt(1, ticketId);
            ps.setInt(2, venueId);
            ps.setString(3, section);
            ps.executeUpdate();
        }
    }
}
