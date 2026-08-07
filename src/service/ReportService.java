package service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Analytical reports R1–R9. R9 uses lightweight Java noun-phrase extraction. */
public class ReportService {

    private final Connection conn;

    public ReportService(Connection conn) {
        this.conn = conn;
    }

    // ── R1 ──────────────────────────────────────────────────────────────────

    public void r1RevenueByCity(LocalDate from, LocalDate to) throws SQLException {
        String sql =
                "SELECT pc.city, pc.country,"
                        + " COUNT(*) AS tickets_sold,"
                        + " SUM(t.face_value) AS gross_revenue"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Venue v ON v.venue_id = p.venue_id"
                        + " JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " WHERE t.cancelled = FALSE"
                        + "   AND o.order_date >= ? AND o.order_date < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY pc.country, pc.city"
                        + " ORDER BY gross_revenue DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            printTable(
                    ps,
                    new String[] {"Country", "City", "Tickets", "Gross revenue"},
                    rs ->
                            new String[] {
                                rs.getString("country"),
                                rs.getString("city"),
                                String.valueOf(rs.getInt("tickets_sold")),
                                money(rs.getBigDecimal("gross_revenue"))
                            });
        }
    }

    public void r1RevenueByVenueInCity(String city, LocalDate from, LocalDate to)
            throws SQLException {
        String sql =
                "SELECT v.venue_id, v.name,"
                        + " COUNT(*) AS tickets_sold,"
                        + " SUM(t.face_value) AS gross_revenue"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Venue v ON v.venue_id = p.venue_id"
                        + " JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " WHERE t.cancelled = FALSE AND pc.city = ?"
                        + "   AND o.order_date >= ? AND o.order_date < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY v.venue_id, v.name"
                        + " ORDER BY gross_revenue DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, city);
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            printTable(
                    ps,
                    new String[] {"Venue#", "Venue", "Tickets", "Gross revenue"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("venue_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("tickets_sold")),
                                money(rs.getBigDecimal("gross_revenue"))
                            });
        }
    }

    // ── R2 ──────────────────────────────────────────────────────────────────

    public void r2Counts(String grain) throws SQLException {
        // grain: segment_genre | country | country_city | country_city_venue
        String select;
        String group;
        String[] headers;
        switch (grain) {
            case "country" -> {
                select = "pc.country,";
                group = "pc.country";
                headers = new String[] {"Country", "Events", "Performances"};
            }
            case "country_city" -> {
                select = "pc.country, pc.city,";
                group = "pc.country, pc.city";
                headers = new String[] {"Country", "City", "Events", "Performances"};
            }
            case "country_city_venue" -> {
                select = "pc.country, pc.city, v.name AS venue_name,";
                group = "pc.country, pc.city, v.venue_id, v.name";
                headers = new String[] {"Country", "City", "Venue", "Events", "Performances"};
            }
            default -> {
                select = "g.segment, e.genre,";
                group = "g.segment, e.genre";
                headers = new String[] {"Segment", "Genre", "Events", "Performances"};
            }
        }

        String sql =
                "SELECT "
                        + select
                        + " COUNT(DISTINCT e.event_id) AS events,"
                        + " COUNT(DISTINCT p.performance_id) AS performances"
                        + " FROM Event e"
                        + " JOIN Genre g ON g.genre = e.genre"
                        + " LEFT JOIN Performance p ON p.event_id = e.event_id"
                        + " LEFT JOIN Venue v ON v.venue_id = p.venue_id"
                        + " LEFT JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " GROUP BY "
                        + group
                        + " ORDER BY events DESC, performances DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            printTable(
                    ps,
                    headers,
                    rs -> {
                        List<String> row = new ArrayList<>();
                        for (String h : headers) {
                            if (h.equals("Events")) {
                                row.add(String.valueOf(rs.getInt("events")));
                            } else if (h.equals("Performances")) {
                                row.add(String.valueOf(rs.getInt("performances")));
                            } else if (h.equals("Segment")) {
                                row.add(rs.getString("segment"));
                            } else if (h.equals("Genre")) {
                                row.add(rs.getString("genre"));
                            } else if (h.equals("Country")) {
                                row.add(nullToDash(rs.getString("country")));
                            } else if (h.equals("City")) {
                                row.add(nullToDash(rs.getString("city")));
                            } else if (h.equals("Venue")) {
                                row.add(nullToDash(rs.getString("venue_name")));
                            }
                        }
                        return row.toArray(new String[0]);
                    });
        }
    }

    // ── R3 ──────────────────────────────────────────────────────────────────

    public void r3OrganizerRevenue(String country, String city) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT u.user_id, u.name,");
        if (country != null) {
            sql.append(" pc.country,");
        }
        if (city != null) {
            sql.append(" pc.city,");
        }
        sql.append(" COALESCE(SUM(t.face_value),0) AS gross_revenue")
                .append(" FROM Organizer org")
                .append(" JOIN `User` u ON u.user_id = org.user_id")
                .append(" LEFT JOIN Event e ON e.user_id = org.user_id")
                .append(" LEFT JOIN Performance p ON p.event_id = e.event_id")
                .append(" LEFT JOIN Orders o ON o.performance_id = p.performance_id")
                .append(" LEFT JOIN Ticket t ON t.order_id = o.order_id AND t.cancelled = FALSE")
                .append(" LEFT JOIN Venue v ON v.venue_id = p.venue_id")
                .append(" LEFT JOIN PostalCode pc ON pc.postal_code = v.postal_code")
                .append(" WHERE 1=1");
        if (country != null) {
            sql.append(" AND pc.country = ?");
        }
        if (city != null) {
            sql.append(" AND pc.city = ?");
        }
        sql.append(" GROUP BY u.user_id, u.name");
        if (country != null) {
            sql.append(", pc.country");
        }
        if (city != null) {
            sql.append(", pc.city");
        }
        sql.append(" ORDER BY gross_revenue DESC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (country != null) {
                ps.setString(i++, country);
            }
            if (city != null) {
                ps.setString(i, city);
            }
            printTable(
                    ps,
                    new String[] {"Org#", "Name", "Gross revenue"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                money(rs.getBigDecimal("gross_revenue"))
                            });
        }
    }

    // ── R4 ──────────────────────────────────────────────────────────────────

    public void r4Scalpers() throws SQLException {
        String sql =
                "WITH purchases AS ("
                        + "  SELECT o.user_id, pc.city, COUNT(*) AS tickets_bought"
                        + "  FROM Ticket t"
                        + "  JOIN Orders o ON o.order_id = t.order_id"
                        + "  JOIN Performance p ON p.performance_id = o.performance_id"
                        + "  JOIN Venue v ON v.venue_id = p.venue_id"
                        + "  JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + "  WHERE o.order_date >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)"
                        + "  GROUP BY o.user_id, pc.city"
                        + "), listings AS ("
                        + "  SELECT l.user_id, pc.city, COUNT(*) AS tickets_listed"
                        + "  FROM Listing l"
                        + "  JOIN Ticket t ON t.ticket_id = l.ticket_id"
                        + "  JOIN Orders o ON o.order_id = t.order_id"
                        + "  JOIN Performance p ON p.performance_id = o.performance_id"
                        + "  JOIN Venue v ON v.venue_id = p.venue_id"
                        + "  JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + "  WHERE l.listed_at >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)"
                        + "  GROUP BY l.user_id, pc.city"
                        + ")"
                        + " SELECT u.user_id, u.name, p.city, p.tickets_bought,"
                        + "        COALESCE(l.tickets_listed,0) AS tickets_listed,"
                        + "        ROUND(COALESCE(l.tickets_listed,0) / p.tickets_bought, 2)"
                        + "          AS list_fraction"
                        + " FROM purchases p"
                        + " JOIN `User` u ON u.user_id = p.user_id"
                        + " LEFT JOIN listings l ON l.user_id = p.user_id AND l.city = p.city"
                        + " WHERE p.tickets_bought >= 10"
                        + "   AND COALESCE(l.tickets_listed,0) > p.tickets_bought / 2"
                        + " ORDER BY p.city, list_fraction DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            printTable(
                    ps,
                    new String[] {"User#", "Name", "City", "Bought", "Listed", "Fraction"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                rs.getString("city"),
                                String.valueOf(rs.getInt("tickets_bought")),
                                String.valueOf(rs.getInt("tickets_listed")),
                                String.valueOf(rs.getBigDecimal("list_fraction"))
                            });
        }
    }

    // ── R5 ──────────────────────────────────────────────────────────────────

    public void r5CustomersByOrders(LocalDate from, LocalDate to) throws SQLException {
        String sql =
                "SELECT u.user_id, u.name, COUNT(*) AS order_count"
                        + " FROM Orders o"
                        + " JOIN `User` u ON u.user_id = o.user_id"
                        + " WHERE o.order_date >= ? AND o.order_date < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY u.user_id, u.name"
                        + " ORDER BY order_count DESC, u.name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            printTable(
                    ps,
                    new String[] {"User#", "Name", "Orders"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("order_count"))
                            });
        }
    }

    public void r5CustomersByOrdersPerCity() throws SQLException {
        String sql =
                "SELECT u.user_id, u.name, pc.city, COUNT(*) AS order_count"
                        + " FROM Orders o"
                        + " JOIN `User` u ON u.user_id = o.user_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Venue v ON v.venue_id = p.venue_id"
                        + " JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " WHERE o.order_date >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)"
                        + " GROUP BY u.user_id, u.name, pc.city"
                        + " HAVING COUNT(*) >= 2"
                        + " ORDER BY pc.city, order_count DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            printTable(
                    ps,
                    new String[] {"User#", "Name", "City", "Orders"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                rs.getString("city"),
                                String.valueOf(rs.getInt("order_count"))
                            });
        }
    }

    // ── R6 ──────────────────────────────────────────────────────────────────

    public void r6TopCancellations() throws SQLException {
        System.out.println("  Customers — cancelled tickets (past year):");
        String custSql =
                "SELECT u.user_id, u.name, COUNT(*) AS cancelled_tickets"
                        + " FROM Ticket t"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN `User` u ON u.user_id = o.user_id"
                        + " WHERE t.cancelled = TRUE"
                        + "   AND t.cancellation_reason = 'customer_cancelled'"
                        + "   AND t.cancelled_at >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)"
                        + " GROUP BY u.user_id, u.name"
                        + " ORDER BY cancelled_tickets DESC"
                        + " LIMIT 20";
        try (PreparedStatement ps = conn.prepareStatement(custSql)) {
            printTable(
                    ps,
                    new String[] {"User#", "Name", "Cancelled tickets"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("cancelled_tickets"))
                            });
        }

        System.out.println("  Organizers — cancelled performances (past year):");
        String orgSql =
                "SELECT u.user_id, u.name, COUNT(*) AS cancelled_performances"
                        + " FROM Performance p"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " JOIN `User` u ON u.user_id = e.user_id"
                        + " WHERE p.cancelled = TRUE"
                        + "   AND p.cancelled_at >= DATE_SUB(CURDATE(), INTERVAL 1 YEAR)"
                        + " GROUP BY u.user_id, u.name"
                        + " ORDER BY cancelled_performances DESC";
        try (PreparedStatement ps = conn.prepareStatement(orgSql)) {
            printTable(
                    ps,
                    new String[] {"Org#", "Name", "Cancelled performances"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("user_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("cancelled_performances"))
                            });
        }
    }

    // ── R7 ──────────────────────────────────────────────────────────────────

    public void r7SellThroughByPerformance() throws SQLException {
        String sql =
                "WITH sellable AS ("
                        + "  SELECT p.performance_id,"
                        + "    (SELECT COUNT(*) FROM Seat s"
                        + "     JOIN AssignedTo a ON a.venue_id = s.venue_id"
                        + "       AND a.section_name = s.section_name"
                        + "     WHERE a.performance_id = p.performance_id"
                        + "       AND NOT EXISTS ("
                        + "         SELECT 1 FROM Blocks b"
                        + "         WHERE b.performance_id = p.performance_id"
                        + "           AND b.venue_id = s.venue_id"
                        + "           AND b.section_name = s.section_name"
                        + "           AND b.row_name = s.row_name"
                        + "           AND b.seat_number = s.seat_number)"
                        + "    )"
                        + "    +"
                        + "    (SELECT COALESCE(SUM(g.capacity),0) FROM GASection g"
                        + "     JOIN AssignedTo a ON a.venue_id = g.venue_id"
                        + "       AND a.section_name = g.section_name"
                        + "     WHERE a.performance_id = p.performance_id)"
                        + "    AS sellable_capacity,"
                        + "    (SELECT COUNT(*) FROM Ticket t"
                        + "     JOIN Orders o ON o.order_id = t.order_id"
                        + "     WHERE o.performance_id = p.performance_id"
                        + "       AND t.cancelled = FALSE) AS sold"
                        + "  FROM Performance p"
                        + "  WHERE p.cancelled = FALSE"
                        + ")"
                        + " SELECT p.performance_id, e.name, p.p_datetime,"
                        + "        s.sellable_capacity, s.sold,"
                        + "        ROUND(s.sold / NULLIF(s.sellable_capacity,0), 3) AS sell_through"
                        + " FROM sellable s"
                        + " JOIN Performance p ON p.performance_id = s.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " ORDER BY sell_through DESC, p.p_datetime";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            printTable(
                    ps,
                    new String[] {"Perf#", "Event", "When", "Sellable", "Sold", "Rate"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("performance_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getTimestamp("p_datetime")),
                                String.valueOf(rs.getInt("sellable_capacity")),
                                String.valueOf(rs.getInt("sold")),
                                String.valueOf(rs.getBigDecimal("sell_through"))
                            });
        }
    }

    public void r7SellThroughByTier(int performanceId) throws SQLException {
        String sql =
                "SELECT a.tier_name, pt.price,"
                        + " SUM(CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM Seat s"
                        + "   WHERE s.venue_id = a.venue_id AND s.section_name = a.section_name"
                        + "     AND NOT EXISTS ("
                        + "       SELECT 1 FROM Blocks b WHERE b.performance_id = a.performance_id"
                        + "         AND b.venue_id = s.venue_id AND b.section_name = s.section_name"
                        + "         AND b.row_name = s.row_name AND b.seat_number = s.seat_number)"
                        + " ) ELSE ga.capacity END) AS sellable,"
                        + " SUM(CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM SeatTicket st"
                        + "   JOIN Ticket t ON t.ticket_id = st.ticket_id"
                        + "   JOIN Orders o ON o.order_id = t.order_id"
                        + "   WHERE o.performance_id = a.performance_id"
                        + "     AND st.venue_id = a.venue_id AND st.section_name = a.section_name"
                        + "     AND t.cancelled = FALSE"
                        + " ) ELSE ("
                        + "   SELECT COUNT(*) FROM GATicket gt"
                        + "   JOIN Ticket t ON t.ticket_id = gt.ticket_id"
                        + "   JOIN Orders o ON o.order_id = t.order_id"
                        + "   WHERE o.performance_id = a.performance_id"
                        + "     AND gt.venue_id = a.venue_id AND gt.section_name = a.section_name"
                        + "     AND t.cancelled = FALSE"
                        + " ) END) AS sold"
                        + " FROM AssignedTo a"
                        + " JOIN PriceTier pt ON pt.performance_id = a.performance_id"
                        + "   AND pt.tier_name = a.tier_name"
                        + " LEFT JOIN ReservedSection rs"
                        + "   ON rs.venue_id = a.venue_id AND rs.section_name = a.section_name"
                        + " LEFT JOIN GASection ga"
                        + "   ON ga.venue_id = a.venue_id AND ga.section_name = a.section_name"
                        + " WHERE a.performance_id = ?"
                        + " GROUP BY a.tier_name, pt.price"
                        + " ORDER BY pt.price DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, performanceId);
            printTable(
                    ps,
                    new String[] {"Tier", "Price", "Sellable", "Sold", "Rate"},
                    rs -> {
                        int sellable = rs.getInt("sellable");
                        int sold = rs.getInt("sold");
                        String rate =
                                sellable == 0
                                        ? "n/a"
                                        : String.format(
                                                Locale.US, "%.3f", (double) sold / sellable);
                        return new String[] {
                            rs.getString("tier_name"),
                            money(rs.getBigDecimal("price")),
                            String.valueOf(sellable),
                            String.valueOf(sold),
                            rate
                        };
                    });
        }
    }

    public void r7SoldOutAndWeakByMonth(int year, int month) throws SQLException {
        String sql =
                "WITH rates AS ("
                        + "  SELECT p.performance_id, e.name, pc.city, p.p_datetime,"
                        + "    (SELECT COUNT(*) FROM Seat s"
                        + "     JOIN AssignedTo a ON a.venue_id = s.venue_id"
                        + "       AND a.section_name = s.section_name"
                        + "     WHERE a.performance_id = p.performance_id"
                        + "       AND NOT EXISTS ("
                        + "         SELECT 1 FROM Blocks b"
                        + "         WHERE b.performance_id = p.performance_id"
                        + "           AND b.venue_id = s.venue_id"
                        + "           AND b.section_name = s.section_name"
                        + "           AND b.row_name = s.row_name"
                        + "           AND b.seat_number = s.seat_number)"
                        + "    )"
                        + "    + (SELECT COALESCE(SUM(g.capacity),0) FROM GASection g"
                        + "       JOIN AssignedTo a ON a.venue_id = g.venue_id"
                        + "         AND a.section_name = g.section_name"
                        + "       WHERE a.performance_id = p.performance_id) AS sellable,"
                        + "    (SELECT COUNT(*) FROM Ticket t"
                        + "     JOIN Orders o ON o.order_id = t.order_id"
                        + "     WHERE o.performance_id = p.performance_id"
                        + "       AND t.cancelled = FALSE) AS sold"
                        + "  FROM Performance p"
                        + "  JOIN Event e ON e.event_id = p.event_id"
                        + "  JOIN Venue v ON v.venue_id = p.venue_id"
                        + "  JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + "  WHERE p.cancelled = FALSE"
                        + "    AND YEAR(p.p_datetime) = ? AND MONTH(p.p_datetime) = ?"
                        + ")"
                        + " SELECT performance_id, name, city, p_datetime, sellable, sold,"
                        + "   ROUND(sold/NULLIF(sellable,0),3) AS rate,"
                        + "   CASE"
                        + "     WHEN sellable > 0 AND sold >= sellable THEN 'SOLD OUT'"
                        + "     WHEN sellable > 0 AND sold < sellable * 0.25 THEN 'UNDER 25%'"
                        + "     ELSE 'other'"
                        + "   END AS flag"
                        + " FROM rates"
                        + " WHERE sellable > 0"
                        + "   AND (sold >= sellable OR sold < sellable * 0.25)"
                        + " ORDER BY city, flag, p_datetime";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            printTable(
                    ps,
                    new String[] {"Perf#", "Event", "City", "When", "Sold/Sellable", "Rate", "Flag"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("performance_id")),
                                rs.getString("name"),
                                rs.getString("city"),
                                String.valueOf(rs.getTimestamp("p_datetime")),
                                rs.getInt("sold") + "/" + rs.getInt("sellable"),
                                String.valueOf(rs.getBigDecimal("rate")),
                                rs.getString("flag")
                            });
        }
    }

    // ── R8 ──────────────────────────────────────────────────────────────────

    public void r8ResaleByEvent(LocalDate from, LocalDate to) throws SQLException {
        String sql =
                "SELECT e.event_id, e.name,"
                        + " COUNT(r.resale_id) AS completed_resales,"
                        + " ROUND(AVG(l.list_price / t.face_value), 3) AS avg_markup_ratio,"
                        + " ROUND(SUM(CASE"
                        + "   WHEN l.list_price = ROUND(t.face_value * e.resale_cap_pct / 100, 2)"
                        + "   THEN 1 ELSE 0 END) / NULLIF(COUNT(r.resale_id),0), 3)"
                        + "   AS fraction_at_cap"
                        + " FROM Event e"
                        + " LEFT JOIN Performance p ON p.event_id = e.event_id"
                        + " LEFT JOIN Orders o ON o.performance_id = p.performance_id"
                        + " LEFT JOIN Ticket t ON t.order_id = o.order_id"
                        + " LEFT JOIN Listing l ON l.ticket_id = t.ticket_id"
                        + " LEFT JOIN Resale r ON r.listing_id = l.listing_id"
                        + "   AND r.sold_at >= ? AND r.sold_at < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY e.event_id, e.name"
                        + " HAVING completed_resales > 0"
                        + " ORDER BY completed_resales DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            printTable(
                    ps,
                    new String[] {"Event#", "Name", "Resales", "Avg markup ratio", "Frac at cap"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("event_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("completed_resales")),
                                String.valueOf(rs.getBigDecimal("avg_markup_ratio")),
                                String.valueOf(rs.getBigDecimal("fraction_at_cap"))
                            });
        }
    }

    public void r8Top10ResaleVolume(LocalDate from, LocalDate to) throws SQLException {
        String sql =
                "SELECT e.event_id, e.name, COUNT(*) AS resale_volume"
                        + " FROM Resale r"
                        + " JOIN Listing l ON l.listing_id = r.listing_id"
                        + " JOIN Ticket t ON t.ticket_id = l.ticket_id"
                        + " JOIN Orders o ON o.order_id = t.order_id"
                        + " JOIN Performance p ON p.performance_id = o.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " WHERE r.sold_at >= ? AND r.sold_at < DATE_ADD(?, INTERVAL 1 DAY)"
                        + " GROUP BY e.event_id, e.name"
                        + " ORDER BY resale_volume DESC"
                        + " LIMIT 10";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            printTable(
                    ps,
                    new String[] {"Event#", "Name", "Resale volume"},
                    rs ->
                            new String[] {
                                String.valueOf(rs.getInt("event_id")),
                                rs.getString("name"),
                                String.valueOf(rs.getInt("resale_volume"))
                            });
        }
    }

    // ── R9 ──────────────────────────────────────────────────────────────────

    public void r9NounPhrases(int topN) throws SQLException {
        String sql =
                "SELECT e.event_id, e.name, r.comment_text"
                        + " FROM Review r"
                        + " JOIN Performance p ON p.performance_id = r.performance_id"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " ORDER BY e.event_id";

        Map<Integer, String> eventNames = new LinkedHashMap<>();
        Map<Integer, List<String>> phrasesByEvent = new HashMap<>();

        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int eid = rs.getInt("event_id");
                eventNames.put(eid, rs.getString("name"));
                phrasesByEvent
                        .computeIfAbsent(eid, k -> new ArrayList<>())
                        .addAll(NounPhrases.extract(rs.getString("comment_text")));
            }
        }

        if (eventNames.isEmpty()) {
            System.out.println("  No reviews found.");
            return;
        }

        for (Map.Entry<Integer, String> e : eventNames.entrySet()) {
            Map<String, Integer> freq = new HashMap<>();
            for (String phrase : phrasesByEvent.getOrDefault(e.getKey(), List.of())) {
                freq.merge(phrase, 1, Integer::sum);
            }
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(freq.entrySet());
            ranked.sort(
                    Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(Map.Entry::getKey));

            System.out.printf("  Event #%d — %s%n", e.getKey(), e.getValue());
            int shown = 0;
            for (Map.Entry<String, Integer> phrase : ranked) {
                if (shown >= topN) {
                    break;
                }
                System.out.printf("    %3d  %s%n", phrase.getValue(), phrase.getKey());
                shown++;
            }
            if (shown == 0) {
                System.out.println("    (no noun phrases extracted)");
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RowMapper {
        String[] map(ResultSet rs) throws SQLException;
    }

    private void printTable(PreparedStatement ps, String[] headers, RowMapper mapper)
            throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            System.out.print("  ");
            System.out.println(String.join(" | ", headers));
            System.out.print("  ");
            System.out.println("-".repeat(Math.min(100, headers.length * 18)));
            int n = 0;
            while (rs.next()) {
                n++;
                System.out.print("  ");
                System.out.println(String.join(" | ", mapper.map(rs)));
            }
            System.out.println("  (" + n + " row(s))");
        }
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : String.format(Locale.US, "%.2f", v);
    }

    private static String nullToDash(String s) {
        return s == null ? "—" : s;
    }

    /**
     * Lightweight noun-phrase extractor: content-word unigrams plus bigrams/trigrams
     * after stopword removal. Good enough for a word-cloud style report without an
     * external NLP dependency.
     */
    static final class NounPhrases {
        private static final Set<String> STOP = new HashSet<>();

        static {
            String[] words = {
                "a", "an", "the", "and", "or", "but", "if", "then", "so", "to", "of", "in",
                "on", "at", "for", "from", "with", "by", "as", "is", "was", "were", "be",
                "been", "are", "am", "it", "its", "this", "that", "these", "those", "i",
                "we", "you", "they", "he", "she", "my", "our", "your", "their", "me",
                "him", "her", "us", "them", "not", "no", "yes", "very", "just", "also",
                "than", "too", "into", "over", "after", "before", "about", "up", "out",
                "what", "when", "where", "which", "who", "how", "all", "each", "every",
                "had", "has", "have", "did", "do", "does", "would", "could", "should",
                "will", "can", "may", "might", "while", "during", "through", "between",
                "there", "here", "such", "only", "own", "same", "other", "another",
                "few", "more", "most", "some", "any", "both", "few", "much", "many"
            };
            for (String w : words) {
                STOP.add(w);
            }
        }

        private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z'-]*");

        static List<String> extract(String text) {
            List<String> tokens = new ArrayList<>();
            Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
            while (m.find()) {
                String t = m.group();
                if (!STOP.contains(t) && t.length() > 2) {
                    tokens.add(t);
                }
            }
            List<String> phrases = new ArrayList<>();
            for (String t : tokens) {
                phrases.add(t);
            }
            for (int i = 0; i + 1 < tokens.size(); i++) {
                phrases.add(tokens.get(i) + " " + tokens.get(i + 1));
            }
            for (int i = 0; i + 2 < tokens.size(); i++) {
                phrases.add(
                        tokens.get(i) + " " + tokens.get(i + 1) + " " + tokens.get(i + 2));
            }
            return phrases;
        }
    }
}
