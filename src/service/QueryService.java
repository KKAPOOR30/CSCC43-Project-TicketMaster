package service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Search queries Q1–Q7. Distance is great-circle kilometres (Haversine).
 * Adjacent postal codes share the same 3-character compact prefix, or the
 * same 2-character prefix within the same city (covers FSA clusters like M5*).
 */
public class QueryService {

    public static final double DEFAULT_RADIUS_KM = 50.0;

    private final Connection conn;

    public QueryService(Connection conn) {
        this.conn = conn;
    }

    // ── Shared SQL fragments ────────────────────────────────────────────────

    /** Haversine distance in km from (lat, lon) to venue coordinates. */
    private static String distanceExpr(String latParam, String lonParam) {
        return "6371 * ACOS(LEAST(1, GREATEST(-1,"
                + " COS(RADIANS(" + latParam + ")) * COS(RADIANS(v.latitude))"
                + " * COS(RADIANS(v.longitude) - RADIANS(" + lonParam + "))"
                + " + SIN(RADIANS(" + latParam + ")) * SIN(RADIANS(v.latitude))"
                + ")))";
    }

    /**
     * Remaining sellable inventory for a performance (reserved free seats + GA
     * remaining). Blocked seats are not sellable.
     */
    private static final String AVAILABLE_SQL =
            "("
                    + " (SELECT COUNT(*) FROM Seat s"
                    + "  JOIN AssignedTo a ON a.venue_id = s.venue_id"
                    + "    AND a.section_name = s.section_name"
                    + "  WHERE a.performance_id = p.performance_id"
                    + "    AND NOT EXISTS ("
                    + "      SELECT 1 FROM SeatTicket st"
                    + "      JOIN Ticket t ON t.ticket_id = st.ticket_id"
                    + "      JOIN Orders o ON o.order_id = t.order_id"
                    + "      WHERE o.performance_id = p.performance_id"
                    + "        AND st.venue_id = s.venue_id"
                    + "        AND st.section_name = s.section_name"
                    + "        AND st.row_name = s.row_name"
                    + "        AND st.seat_number = s.seat_number"
                    + "        AND t.cancelled = FALSE)"
                    + "    AND NOT EXISTS ("
                    + "      SELECT 1 FROM Blocks b"
                    + "      WHERE b.performance_id = p.performance_id"
                    + "        AND b.venue_id = s.venue_id"
                    + "        AND b.section_name = s.section_name"
                    + "        AND b.row_name = s.row_name"
                    + "        AND b.seat_number = s.seat_number)"
                    + " )"
                    + " +"
                    + " (SELECT COALESCE(SUM("
                    + "    g.capacity - ("
                    + "      SELECT COUNT(*) FROM GATicket gt"
                    + "      JOIN Ticket t ON t.ticket_id = gt.ticket_id"
                    + "      JOIN Orders o ON o.order_id = t.order_id"
                    + "      WHERE o.performance_id = p.performance_id"
                    + "        AND gt.venue_id = g.venue_id"
                    + "        AND gt.section_name = g.section_name"
                    + "        AND t.cancelled = FALSE)"
                    + "  ), 0)"
                    + "  FROM GASection g"
                    + "  JOIN AssignedTo a ON a.venue_id = g.venue_id"
                    + "    AND a.section_name = g.section_name"
                    + "  WHERE a.performance_id = p.performance_id)"
                    + ")";

    /** Cheapest face price among tiers that still have at least one available unit. */
    private static final String CHEAPEST_AVAILABLE_SQL =
            "("
                    + " SELECT MIN(pt.price) FROM PriceTier pt"
                    + " WHERE pt.performance_id = p.performance_id"
                    + "   AND EXISTS ("
                    + "     SELECT 1 FROM AssignedTo a"
                    + "     WHERE a.performance_id = pt.performance_id"
                    + "       AND a.tier_name = pt.tier_name"
                    + "       AND ("
                    + "         EXISTS ("
                    + "           SELECT 1 FROM Seat s"
                    + "           WHERE s.venue_id = a.venue_id AND s.section_name = a.section_name"
                    + "             AND NOT EXISTS ("
                    + "               SELECT 1 FROM SeatTicket st"
                    + "               JOIN Ticket t ON t.ticket_id = st.ticket_id"
                    + "               JOIN Orders o ON o.order_id = t.order_id"
                    + "               WHERE o.performance_id = p.performance_id"
                    + "                 AND st.venue_id = s.venue_id"
                    + "                 AND st.section_name = s.section_name"
                    + "                 AND st.row_name = s.row_name"
                    + "                 AND st.seat_number = s.seat_number"
                    + "                 AND t.cancelled = FALSE)"
                    + "             AND NOT EXISTS ("
                    + "               SELECT 1 FROM Blocks b"
                    + "               WHERE b.performance_id = p.performance_id"
                    + "                 AND b.venue_id = s.venue_id"
                    + "                 AND b.section_name = s.section_name"
                    + "                 AND b.row_name = s.row_name"
                    + "                 AND b.seat_number = s.seat_number)"
                    + "         )"
                    + "         OR EXISTS ("
                    + "           SELECT 1 FROM GASection g"
                    + "           WHERE g.venue_id = a.venue_id AND g.section_name = a.section_name"
                    + "             AND g.capacity > ("
                    + "               SELECT COUNT(*) FROM GATicket gt"
                    + "               JOIN Ticket t ON t.ticket_id = gt.ticket_id"
                    + "               JOIN Orders o ON o.order_id = t.order_id"
                    + "               WHERE o.performance_id = p.performance_id"
                    + "                 AND gt.venue_id = g.venue_id"
                    + "                 AND gt.section_name = g.section_name"
                    + "                 AND t.cancelled = FALSE)"
                    + "         )"
                    + "       )"
                    + "   )"
                    + ")";

    private static final String ADJACENT_POSTAL =
            "("
                    + " LEFT(REPLACE(UPPER(pc.postal_code),' ',''), 3)"
                    + "   = LEFT(REPLACE(UPPER(?),' ',''), 3)"
                    + " OR ("
                    + "   pc.city = (SELECT city FROM PostalCode WHERE postal_code = ?)"
                    + "   AND LEFT(REPLACE(UPPER(pc.postal_code),' ',''), 2)"
                    + "     = LEFT(REPLACE(UPPER(?),' ',''), 2)"
                    + " )"
                    + ")";

    // ── Q1 ──────────────────────────────────────────────────────────────────

    public void q1Nearby(
            double lat,
            double lon,
            double radiusKm,
            String rankBy, // "distance" | "price_asc" | "price_desc"
            LocalDateTime from,
            LocalDateTime to,
            Integer minAvailable)
            throws SQLException {
        String dist = distanceExpr(Double.toString(lat), Double.toString(lon));
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.performance_id, e.name AS event_name, v.name AS venue_name,")
                .append(" pc.city, p.p_datetime, ")
                .append(dist)
                .append(" AS distance_km, ")
                .append(CHEAPEST_AVAILABLE_SQL)
                .append(" AS cheapest, ")
                .append(AVAILABLE_SQL)
                .append(" AS available")
                .append(" FROM Performance p")
                .append(" JOIN Event e ON e.event_id = p.event_id")
                .append(" JOIN Venue v ON v.venue_id = p.venue_id")
                .append(" JOIN PostalCode pc ON pc.postal_code = v.postal_code")
                .append(" WHERE p.cancelled = FALSE AND p.p_datetime > NOW()")
                .append("   AND ")
                .append(dist)
                .append(" <= ?");

        if (from != null) {
            sql.append(" AND p.p_datetime >= ?");
        }
        if (to != null) {
            sql.append(" AND p.p_datetime <= ?");
        }

        sql.append(" HAVING 1=1");
        if (minAvailable != null) {
            sql.append(" AND available >= ?");
        }

        switch (rankBy == null ? "distance" : rankBy) {
            case "price_asc" -> sql.append(" ORDER BY cheapest IS NULL, cheapest ASC, distance_km");
            case "price_desc" ->
                    sql.append(" ORDER BY cheapest IS NULL, cheapest DESC, distance_km");
            default -> sql.append(" ORDER BY distance_km, p.p_datetime");
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setDouble(i++, radiusKm);
            if (from != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(from));
            }
            if (to != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(to));
            }
            if (minAvailable != null) {
                ps.setInt(i++, minAvailable);
            }
            printPerformanceRows(ps, true);
        }
    }

    // ── Q2 ──────────────────────────────────────────────────────────────────

    public void q2ByPostal(
            String postalCode,
            LocalDateTime from,
            LocalDateTime to,
            Integer minAvailable)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.performance_id, e.name AS event_name, v.name AS venue_name,")
                .append(" pc.city, pc.postal_code, p.p_datetime, ")
                .append(CHEAPEST_AVAILABLE_SQL)
                .append(" AS cheapest, ")
                .append(AVAILABLE_SQL)
                .append(" AS available")
                .append(" FROM Performance p")
                .append(" JOIN Event e ON e.event_id = p.event_id")
                .append(" JOIN Venue v ON v.venue_id = p.venue_id")
                .append(" JOIN PostalCode pc ON pc.postal_code = v.postal_code")
                .append(" WHERE p.cancelled = FALSE AND p.p_datetime > NOW()")
                .append("   AND ")
                .append(ADJACENT_POSTAL);
        if (from != null) {
            sql.append(" AND p.p_datetime >= ?");
        }
        if (to != null) {
            sql.append(" AND p.p_datetime <= ?");
        }
        sql.append(" HAVING 1=1");
        if (minAvailable != null) {
            sql.append(" AND available >= ?");
        }
        sql.append(" ORDER BY pc.postal_code, p.p_datetime");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, postalCode);
            ps.setString(i++, postalCode);
            ps.setString(i++, postalCode);
            if (from != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(from));
            }
            if (to != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(to));
            }
            if (minAvailable != null) {
                ps.setInt(i, minAvailable);
            }
            printPerformanceRows(ps, false);
        }
    }

    // ── Q3 ──────────────────────────────────────────────────────────────────

    public void q3ByAddress(
            String streetAddress,
            LocalDateTime from,
            LocalDateTime to,
            Integer minAvailable)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT v.venue_id, v.name AS venue_name, v.street_address,")
                .append(" pc.postal_code, pc.city, pc.country,")
                .append(" p.performance_id, e.name AS event_name, p.p_datetime, ")
                .append(CHEAPEST_AVAILABLE_SQL)
                .append(" AS cheapest, ")
                .append(AVAILABLE_SQL)
                .append(" AS available")
                .append(" FROM Venue v")
                .append(" JOIN PostalCode pc ON pc.postal_code = v.postal_code")
                .append(" LEFT JOIN Performance p ON p.venue_id = v.venue_id")
                .append("   AND p.cancelled = FALSE AND p.p_datetime > NOW()");
        if (from != null) {
            sql.append(" AND p.p_datetime >= ?");
        }
        if (to != null) {
            sql.append(" AND p.p_datetime <= ?");
        }
        sql.append(" LEFT JOIN Event e ON e.event_id = p.event_id")
                .append(" WHERE v.street_address = ?");
        if (minAvailable != null) {
            sql.append(" HAVING p.performance_id IS NULL OR available >= ?");
        }
        sql.append(" ORDER BY p.p_datetime");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (from != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(from));
            }
            if (to != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(to));
            }
            ps.setString(i++, streetAddress);
            if (minAvailable != null) {
                ps.setInt(i, minAvailable);
            }
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf(
                            "  venue #%d  %s%n    %s, %s %s, %s%n",
                            rs.getInt("venue_id"),
                            rs.getString("venue_name"),
                            rs.getString("street_address"),
                            rs.getString("city"),
                            rs.getString("postal_code"),
                            rs.getString("country"));
                    int pid = rs.getInt("performance_id");
                    if (!rs.wasNull()) {
                        System.out.printf(
                                "    perf #%d  %s  \"%s\"  cheapest=%s  avail=%s%n",
                                pid,
                                rs.getTimestamp("p_datetime"),
                                rs.getString("event_name"),
                                rs.getBigDecimal("cheapest"),
                                rs.getObject("available"));
                    }
                }
                if (!any) {
                    System.out.println("  No venue at that exact address.");
                }
            }
        }
    }

    // ── Q5 (also covers filtered Q4-style refinements) ──────────────────────

    public void q5Filter(
            String city,
            String segment,
            String genre,
            LocalDateTime from,
            LocalDateTime to,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Integer minAvailable,
            String seatingType // "reserved" | "ga" | null
            ) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.performance_id, e.name AS event_name, g.segment, e.genre,")
                .append(" v.name AS venue_name, pc.city, p.p_datetime, ")
                .append(CHEAPEST_AVAILABLE_SQL)
                .append(" AS cheapest, ")
                .append(AVAILABLE_SQL)
                .append(" AS available")
                .append(" FROM Performance p")
                .append(" JOIN Event e ON e.event_id = p.event_id")
                .append(" JOIN Genre g ON g.genre = e.genre")
                .append(" JOIN Venue v ON v.venue_id = p.venue_id")
                .append(" JOIN PostalCode pc ON pc.postal_code = v.postal_code")
                .append(" WHERE p.cancelled = FALSE AND p.p_datetime > NOW()");

        if (city != null && !city.isBlank()) {
            sql.append(" AND pc.city = ?");
        }
        if (segment != null && !segment.isBlank()) {
            sql.append(" AND g.segment = ?");
        }
        if (genre != null && !genre.isBlank()) {
            sql.append(" AND e.genre = ?");
        }
        if (from != null) {
            sql.append(" AND p.p_datetime >= ?");
        }
        if (to != null) {
            sql.append(" AND p.p_datetime <= ?");
        }
        if (seatingType != null) {
            if (seatingType.equalsIgnoreCase("reserved")) {
                sql.append(
                        " AND EXISTS (SELECT 1 FROM AssignedTo a"
                                + " JOIN ReservedSection r ON r.venue_id = a.venue_id"
                                + "   AND r.section_name = a.section_name"
                                + " WHERE a.performance_id = p.performance_id)");
            } else if (seatingType.equalsIgnoreCase("ga")) {
                sql.append(
                        " AND EXISTS (SELECT 1 FROM AssignedTo a"
                                + " JOIN GASection ga ON ga.venue_id = a.venue_id"
                                + "   AND ga.section_name = a.section_name"
                                + " WHERE a.performance_id = p.performance_id)");
            }
        }

        sql.append(" HAVING 1=1");
        if (minAvailable != null) {
            sql.append(" AND available >= ?");
        }
        if (minPrice != null) {
            sql.append(" AND cheapest >= ?");
        }
        if (maxPrice != null) {
            sql.append(" AND cheapest <= ?");
        }
        sql.append(" ORDER BY p.p_datetime");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (city != null && !city.isBlank()) {
                ps.setString(i++, city);
            }
            if (segment != null && !segment.isBlank()) {
                ps.setString(i++, segment);
            }
            if (genre != null && !genre.isBlank()) {
                ps.setString(i++, genre);
            }
            if (from != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(from));
            }
            if (to != null) {
                ps.setTimestamp(i++, Timestamp.valueOf(to));
            }
            if (minAvailable != null) {
                ps.setInt(i++, minAvailable);
            }
            if (minPrice != null) {
                ps.setBigDecimal(i++, minPrice);
            }
            if (maxPrice != null) {
                ps.setBigDecimal(i, maxPrice);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    n++;
                    System.out.printf(
                            "  #%d  %s  \"%s\" [%s/%s]%n"
                                    + "      @ %s, %s  cheapest=%s  avail=%d%n",
                            rs.getInt("performance_id"),
                            rs.getTimestamp("p_datetime"),
                            rs.getString("event_name"),
                            rs.getString("segment"),
                            rs.getString("genre"),
                            rs.getString("venue_name"),
                            rs.getString("city"),
                            rs.getBigDecimal("cheapest"),
                            rs.getInt("available"));
                }
                System.out.println("  (" + n + " result(s))");
            }
        }
    }

    // ── Q6 ──────────────────────────────────────────────────────────────────

    public void q6SeatMap(int performanceId) throws SQLException {
        String sql =
                "SELECT a.section_name, a.tier_name, pt.price,"
                        + " CASE WHEN rs.section_name IS NOT NULL THEN 'reserved' ELSE 'ga' END AS kind,"
                        + " CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM Seat s"
                        + "   WHERE s.venue_id = a.venue_id AND s.section_name = a.section_name"
                        + "     AND NOT EXISTS ("
                        + "       SELECT 1 FROM SeatTicket st"
                        + "       JOIN Ticket t ON t.ticket_id = st.ticket_id"
                        + "       JOIN Orders o ON o.order_id = t.order_id"
                        + "       WHERE o.performance_id = a.performance_id"
                        + "         AND st.venue_id = s.venue_id"
                        + "         AND st.section_name = s.section_name"
                        + "         AND st.row_name = s.row_name"
                        + "         AND st.seat_number = s.seat_number"
                        + "         AND t.cancelled = FALSE)"
                        + "     AND NOT EXISTS ("
                        + "       SELECT 1 FROM Blocks b"
                        + "       WHERE b.performance_id = a.performance_id"
                        + "         AND b.venue_id = s.venue_id"
                        + "         AND b.section_name = s.section_name"
                        + "         AND b.row_name = s.row_name"
                        + "         AND b.seat_number = s.seat_number)"
                        + " ) ELSE ("
                        + "   ga.capacity - ("
                        + "     SELECT COUNT(*) FROM GATicket gt"
                        + "     JOIN Ticket t ON t.ticket_id = gt.ticket_id"
                        + "     JOIN Orders o ON o.order_id = t.order_id"
                        + "     WHERE o.performance_id = a.performance_id"
                        + "       AND gt.venue_id = ga.venue_id"
                        + "       AND gt.section_name = ga.section_name"
                        + "       AND t.cancelled = FALSE)"
                        + " ) END AS available,"
                        + " CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM SeatTicket st"
                        + "   JOIN Ticket t ON t.ticket_id = st.ticket_id"
                        + "   JOIN Orders o ON o.order_id = t.order_id"
                        + "   WHERE o.performance_id = a.performance_id"
                        + "     AND st.venue_id = a.venue_id"
                        + "     AND st.section_name = a.section_name"
                        + "     AND t.cancelled = FALSE"
                        + " ) ELSE ("
                        + "   SELECT COUNT(*) FROM GATicket gt"
                        + "   JOIN Ticket t ON t.ticket_id = gt.ticket_id"
                        + "   JOIN Orders o ON o.order_id = t.order_id"
                        + "   WHERE o.performance_id = a.performance_id"
                        + "     AND gt.venue_id = a.venue_id"
                        + "     AND gt.section_name = a.section_name"
                        + "     AND t.cancelled = FALSE"
                        + " ) END AS sold,"
                        + " CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM Blocks b"
                        + "   WHERE b.performance_id = a.performance_id"
                        + "     AND b.venue_id = a.venue_id"
                        + "     AND b.section_name = a.section_name"
                        + " ) ELSE 0 END AS blocked"
                        + " FROM AssignedTo a"
                        + " JOIN PriceTier pt ON pt.performance_id = a.performance_id"
                        + "   AND pt.tier_name = a.tier_name"
                        + " LEFT JOIN ReservedSection rs"
                        + "   ON rs.venue_id = a.venue_id AND rs.section_name = a.section_name"
                        + " LEFT JOIN GASection ga"
                        + "   ON ga.venue_id = a.venue_id AND ga.section_name = a.section_name"
                        + " WHERE a.performance_id = ?"
                        + " ORDER BY a.section_name";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.printf(
                        "  %-22s %-8s %8s %-10s %8s %6s %8s%n",
                        "Section", "Tier", "Price", "Type", "Avail", "Sold", "Blocked");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf(
                            "  %-22s %-8s %8.2f %-10s %8d %6d %8d%n",
                            rs.getString("section_name"),
                            rs.getString("tier_name"),
                            rs.getBigDecimal("price"),
                            rs.getString("kind"),
                            rs.getInt("available"),
                            rs.getInt("sold"),
                            rs.getInt("blocked"));
                }
                if (!any) {
                    System.out.println("  No section assignments for this performance.");
                }
            }
        }
    }

    // ── Q7 ──────────────────────────────────────────────────────────────────

    /**
     * Finds {@code q} consecutive seats in the same row with the lowest total
     * price (optional budget). Uses a self-join / window approach over free seats.
     */
    public void q7BestAvailable(int performanceId, int q, BigDecimal budget) throws SQLException {
        if (q <= 0) {
            throw new SQLException("Quantity must be positive.");
        }

        String sql =
                "WITH free_seats AS ("
                        + "  SELECT s.section_name, s.row_name, s.seat_number, pt.price"
                        + "  FROM Seat s"
                        + "  JOIN AssignedTo a ON a.venue_id = s.venue_id"
                        + "    AND a.section_name = s.section_name"
                        + "  JOIN PriceTier pt ON pt.performance_id = a.performance_id"
                        + "    AND pt.tier_name = a.tier_name"
                        + "  JOIN Performance p ON p.performance_id = a.performance_id"
                        + "  WHERE a.performance_id = ?"
                        + "    AND p.cancelled = FALSE"
                        + "    AND NOT EXISTS ("
                        + "      SELECT 1 FROM SeatTicket st"
                        + "      JOIN Ticket t ON t.ticket_id = st.ticket_id"
                        + "      JOIN Orders o ON o.order_id = t.order_id"
                        + "      WHERE o.performance_id = ?"
                        + "        AND st.venue_id = s.venue_id"
                        + "        AND st.section_name = s.section_name"
                        + "        AND st.row_name = s.row_name"
                        + "        AND st.seat_number = s.seat_number"
                        + "        AND t.cancelled = FALSE)"
                        + "    AND NOT EXISTS ("
                        + "      SELECT 1 FROM Blocks b"
                        + "      WHERE b.performance_id = ?"
                        + "        AND b.venue_id = s.venue_id"
                        + "        AND b.section_name = s.section_name"
                        + "        AND b.row_name = s.row_name"
                        + "        AND b.seat_number = s.seat_number)"
                        + "), candidates AS ("
                        + "  SELECT f1.section_name, f1.row_name,"
                        + "         f1.seat_number AS start_seat,"
                        + "         f1.seat_number + ? - 1 AS end_seat,"
                        + "         (SELECT SUM(f2.price) FROM free_seats f2"
                        + "          WHERE f2.section_name = f1.section_name"
                        + "            AND f2.row_name = f1.row_name"
                        + "            AND f2.seat_number BETWEEN f1.seat_number"
                        + "                AND f1.seat_number + ? - 1) AS total_price,"
                        + "         (SELECT COUNT(*) FROM free_seats f2"
                        + "          WHERE f2.section_name = f1.section_name"
                        + "            AND f2.row_name = f1.row_name"
                        + "            AND f2.seat_number BETWEEN f1.seat_number"
                        + "                AND f1.seat_number + ? - 1) AS seat_count"
                        + "  FROM free_seats f1"
                        + ")"
                        + " SELECT section_name, row_name, start_seat, end_seat, total_price"
                        + " FROM candidates"
                        + " WHERE seat_count = ?"
                        + (budget != null ? " AND total_price <= ?" : "")
                        + " ORDER BY total_price ASC, section_name, row_name, start_seat"
                        + " LIMIT 5";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, performanceId);
            ps.setInt(i++, performanceId);
            ps.setInt(i++, performanceId);
            ps.setInt(i++, q);
            ps.setInt(i++, q);
            ps.setInt(i++, q);
            ps.setInt(i++, q);
            if (budget != null) {
                ps.setBigDecimal(i, budget);
            }
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                System.out.println("  Best blocks of " + q + " consecutive seats:");
                while (rs.next()) {
                    any = true;
                    System.out.printf(
                            "  %s / row %s / seats %d-%d  total $%s%n",
                            rs.getString("section_name"),
                            rs.getString("row_name"),
                            rs.getInt("start_seat"),
                            rs.getInt("end_seat"),
                            rs.getBigDecimal("total_price"));
                }
                if (!any) {
                    System.out.println(
                            "  No consecutive block of "
                                    + q
                                    + " available seats"
                                    + (budget != null ? " within budget $" + budget : "")
                                    + ".");
                }
            }
        }
    }

    // ── printing ────────────────────────────────────────────────────────────

    private void printPerformanceRows(PreparedStatement ps, boolean withDistance)
            throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            int n = 0;
            while (rs.next()) {
                n++;
                System.out.printf(
                        "  #%d  %s  \"%s\"%n      @ %s, %s",
                        rs.getInt("performance_id"),
                        rs.getTimestamp("p_datetime"),
                        rs.getString("event_name"),
                        rs.getString("venue_name"),
                        rs.getString("city"));
                if (withDistance) {
                    System.out.printf("  dist=%.2f km", rs.getDouble("distance_km"));
                }
                try {
                    rs.findColumn("postal_code");
                    System.out.printf("  [%s]", rs.getString("postal_code"));
                } catch (SQLException ignored) {
                    // column absent
                }
                System.out.printf(
                        "  cheapest=%s  avail=%s%n",
                        rs.getBigDecimal("cheapest"), rs.getObject("available"));
            }
            System.out.println("  (" + n + " result(s))");
        }
    }
}
