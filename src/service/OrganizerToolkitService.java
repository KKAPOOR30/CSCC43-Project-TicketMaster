package service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Organizer toolkit: suggests a tier structure and prices for a new
 * performance from comparable past performances (same genre, venue capacity
 * within +-35%, same city weighted higher), and estimates the revenue impact
 * of a proposed tier price change from a linear demand fit over comparable
 * (price, sold) observations.
 */
public class OrganizerToolkitService {

    private final Connection conn;

    public OrganizerToolkitService(Connection conn) {
        this.conn = conn;
    }

    // ── Public entry points ─────────────────────────────────────────────────

    /**
     * Suggests how many tiers to define, what share of capacity each should
     * get, and a price for each tier, for a new performance at {@code venueId}
     * in {@code genre}.
     */
    public void suggestForNewPerformance(int venueId, String genre) throws SQLException {
        VenueCap venue = loadVenue(venueId);
        if (venue == null) {
            throw new SQLException("Venue not found.");
        }

        System.out.println();
        System.out.println("=== Organizer toolkit: new-performance suggestion ===");
        System.out.printf(
                Locale.US,
                "  Target: %s (#%d) in %s — sellable capacity %d%n",
                venue.name,
                venue.venueId,
                venue.city,
                venue.capacity);
        System.out.printf("  Genre: %s%n", genre);
        System.out.println();
        System.out.println(
                "  Method: median tier count & capacity-weighted average prices");
        System.out.println(
                "  from same-genre performances (24 mo), capacity ±35%;");
        System.out.println("  same-city comps weighted 2×.");
        System.out.println();

        List<ComparablePerf> comps = findComparables(genre, venue.capacity, venue.city);
        System.out.printf("  Comparable performances found: %d%n", comps.size());

        List<TierSuggestion> suggestion = buildSuggestion(comps, venue);
        printSuggestion(suggestion);

        if (!comps.isEmpty()) {
            System.out.println("  Sample comparables:");
            int shown = 0;
            for (ComparablePerf c : comps) {
                if (shown >= 5) {
                    break;
                }
                System.out.printf(
                        Locale.US,
                        "    perf #%d  %s  @ %s (%s)  cap=%d  tiers=%d  sameCity=%s%n",
                        c.performanceId,
                        c.eventName,
                        c.venueName,
                        c.city,
                        c.capacity,
                        c.tiers.size(),
                        c.sameCity);
                shown++;
            }
        }
    }

    /**
     * Estimates expected revenue change if {@code tierName} on
     * {@code performanceId} moves to {@code newPrice}.
     */
    public void estimatePriceChangeImpact(
            int performanceId, String tierName, BigDecimal newPrice) throws SQLException {
        if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SQLException("New price must be positive.");
        }

        TierState tier = loadTierState(performanceId, tierName);
        if (tier == null) {
            throw new SQLException("Tier not found on this performance.");
        }

        String genre = genreOf(performanceId);
        VenueCap venue = venueOfPerformance(performanceId);
        List<ComparablePerf> comps =
                findComparables(genre, venue.capacity, venue.city);

        // Collect (price, sold) observations for the same tier rank among comps.
        int rank = tierRank(performanceId, tierName);
        List<double[]> points = new ArrayList<>();
        for (ComparablePerf c : comps) {
            if (c.tiers.size() < rank) {
                continue;
            }
            TierObs t = c.tiers.get(rank - 1); // rank 1 = index 0 (highest price)
            if (t.sellable <= 0) {
                continue;
            }
            double w = c.sameCity ? 2.0 : 1.0;
            for (int k = 0; k < (int) w; k++) {
                points.add(new double[] {t.price, t.sold});
            }
        }

        System.out.println();
        System.out.println("=== Organizer toolkit: price-change impact ===");
        System.out.printf(
                Locale.US,
                "  Performance #%d  tier %s%n",
                performanceId,
                tierName);
        System.out.printf(
                Locale.US,
                "  Current price $%.2f  →  proposed $%.2f%n",
                tier.price,
                newPrice);
        System.out.printf(
                Locale.US,
                "  Current sold %d / sellable %d%n",
                tier.sold,
                tier.sellable);
        System.out.printf("  Demand model points from comparables: %d%n", points.size());

        double oldP = tier.price;
        double newP = newPrice.doubleValue();
        double expectedOldSold;
        double expectedNewSold;
        String modelNote;

        if (points.size() >= 2) {
            double[] ab = fitLinearDemand(points);
            // sold = a - b*price
            expectedOldSold = clamp(ab[0] - ab[1] * oldP, 0, tier.sellable);
            expectedNewSold = clamp(ab[0] - ab[1] * newP, 0, tier.sellable);
            modelNote =
                    String.format(
                            Locale.US,
                            "linear demand sold ≈ %.1f − %.3f·price (from comps)",
                            ab[0],
                            ab[1]);
        } else {
            // Fallback: constant-elasticity-style heuristic using current sell-through.
            // Assume 10% price increase → 8% demand drop (elasticity −0.8).
            double elasticity = -0.8;
            double currentSold = Math.max(tier.sold, tier.sellable * 0.15);
            double pctPrice = (newP - oldP) / oldP;
            expectedOldSold = currentSold;
            expectedNewSold =
                    clamp(currentSold * (1.0 + elasticity * pctPrice), 0, tier.sellable);
            modelNote =
                    "fallback elasticity −0.8 on current (or 15% baseline) sell-through";
        }

        double oldRev = oldP * expectedOldSold;
        double newRev = newP * expectedNewSold;
        double delta = newRev - oldRev;

        System.out.println("  Model: " + modelNote);
        System.out.printf(
                Locale.US,
                "  Expected sold @ current price: %.1f  (rev $%.2f)%n",
                expectedOldSold,
                oldRev);
        System.out.printf(
                Locale.US,
                "  Expected sold @ new price:     %.1f  (rev $%.2f)%n",
                expectedNewSold,
                newRev);
        System.out.printf(
                Locale.US,
                "  Estimated revenue change: %s$%.2f%n",
                delta >= 0 ? "+" : "",
                delta);
        System.out.println(
                "  (Rough positioning aid only — not a guaranteed forecast.)");
    }

    // ── Suggestion construction ─────────────────────────────────────────────

    private List<TierSuggestion> buildSuggestion(List<ComparablePerf> comps, VenueCap venue) {
        int tierCount;
        double[] shares;
        double[] prices;

        if (comps.size() >= 3) {
            List<Integer> counts = new ArrayList<>();
            for (ComparablePerf c : comps) {
                counts.add(c.tiers.size());
            }
            counts.sort(Integer::compareTo);
            tierCount = counts.get(counts.size() / 2);
            tierCount = Math.max(2, Math.min(4, tierCount));

            shares = averageShares(comps, tierCount);
            prices = averagePrices(comps, tierCount);
        } else {
            if (venue.capacity < 120) {
                tierCount = 2;
                shares = new double[] {0.40, 0.60};
                prices = new double[] {140, 75};
            } else if (venue.capacity < 250) {
                tierCount = 3;
                shares = new double[] {0.25, 0.35, 0.40};
                prices = new double[] {180, 110, 65};
            } else {
                tierCount = 3;
                shares = new double[] {0.20, 0.35, 0.45};
                prices = new double[] {220, 130, 70};
            }
            if (!comps.isEmpty()) {
                double[] fromComps = averagePrices(comps, tierCount);
                for (int i = 0; i < tierCount; i++) {
                    if (fromComps[i] > 0) {
                        prices[i] = fromComps[i];
                    }
                }
            }
        }

        normalize(shares);
        List<TierSuggestion> out = new ArrayList<>();
        for (int i = 0; i < tierCount; i++) {
            String name = "P" + (i + 1);
            int seats = (int) Math.round(shares[i] * venue.capacity);
            out.add(
                    new TierSuggestion(
                            name,
                            shares[i],
                            seats,
                            BigDecimal.valueOf(prices[i]).setScale(2, RoundingMode.HALF_UP)));
        }
        // Fix rounding so seat shares sum to capacity.
        int sum = out.stream().mapToInt(t -> t.seatShare).sum();
        if (!out.isEmpty() && sum != venue.capacity) {
            out.get(out.size() - 1).seatShare += (venue.capacity - sum);
        }
        return out;
    }

    private double[] averageShares(List<ComparablePerf> comps, int tierCount) {
        double[] sum = new double[tierCount];
        double[] weight = new double[tierCount];
        for (ComparablePerf c : comps) {
            double w = c.sameCity ? 2.0 : 1.0;
            double[] s = capacityShares(c, tierCount);
            for (int i = 0; i < tierCount; i++) {
                sum[i] += w * s[i];
                weight[i] += w;
            }
        }
        double[] out = new double[tierCount];
        for (int i = 0; i < tierCount; i++) {
            out[i] = weight[i] == 0 ? 1.0 / tierCount : sum[i] / weight[i];
        }
        return out;
    }

    private double[] averagePrices(List<ComparablePerf> comps, int tierCount) {
        double[] sum = new double[tierCount];
        double[] weight = new double[tierCount];
        for (ComparablePerf c : comps) {
            double w = c.sameCity ? 2.0 : 1.0;
            List<TierObs> ranked = new ArrayList<>(c.tiers);
            ranked.sort(Comparator.comparingDouble((TierObs t) -> t.price).reversed());
            for (int i = 0; i < tierCount; i++) {
                if (i < ranked.size()) {
                    // weight by sellable capacity of that tier
                    double tw = w * Math.max(1, ranked.get(i).sellable);
                    sum[i] += tw * ranked.get(i).price;
                    weight[i] += tw;
                }
            }
        }
        double[] out = new double[tierCount];
        for (int i = 0; i < tierCount; i++) {
            out[i] = weight[i] == 0 ? 0 : Math.round(sum[i] / weight[i]);
        }
        // Ensure strictly decreasing prices for clarity.
        for (int i = 1; i < tierCount; i++) {
            if (out[i] <= 0 && out[i - 1] > 0) {
                out[i] = Math.max(10, out[i - 1] * 0.65);
            }
            if (out[i] >= out[i - 1] && out[i - 1] > 0) {
                out[i] = Math.max(10, Math.round(out[i - 1] * 0.75));
            }
        }
        return out;
    }

    /** Map a comparable's tiers onto {@code tierCount} ranks by price. */
    private double[] capacityShares(ComparablePerf c, int tierCount) {
        List<TierObs> ranked = new ArrayList<>(c.tiers);
        ranked.sort(Comparator.comparingDouble((TierObs t) -> t.price).reversed());
        double total = ranked.stream().mapToDouble(t -> t.sellable).sum();
        if (total <= 0) {
            double[] eq = new double[tierCount];
            for (int i = 0; i < tierCount; i++) {
                eq[i] = 1.0 / tierCount;
            }
            return eq;
        }
        double[] out = new double[tierCount];
        if (ranked.size() == tierCount) {
            for (int i = 0; i < tierCount; i++) {
                out[i] = ranked.get(i).sellable / total;
            }
        } else if (ranked.size() > tierCount) {
            // Merge cheapest extras into the last bucket.
            for (int i = 0; i < tierCount - 1; i++) {
                out[i] = ranked.get(i).sellable / total;
            }
            double rest = 0;
            for (int i = tierCount - 1; i < ranked.size(); i++) {
                rest += ranked.get(i).sellable;
            }
            out[tierCount - 1] = rest / total;
        } else {
            // Fewer tiers than target: stretch by splitting the cheapest.
            for (int i = 0; i < ranked.size(); i++) {
                out[i] = ranked.get(i).sellable / total;
            }
            double leftover = 0;
            for (int i = ranked.size(); i < tierCount; i++) {
                leftover += 1.0 / tierCount;
                out[i] = 1.0 / tierCount;
            }
            normalize(out);
        }
        return out;
    }

    private void printSuggestion(List<TierSuggestion> suggestion) {
        System.out.println();
        System.out.println("  Suggested tier structure:");
        System.out.printf(
                Locale.US,
                "  %-6s %10s %12s %12s%n",
                "Tier",
                "Share",
                "≈ Seats",
                "Price");
        for (TierSuggestion t : suggestion) {
            System.out.printf(
                    Locale.US,
                    "  %-6s %9.1f%% %12d %12s%n",
                    t.name,
                    t.share * 100.0,
                    t.seatShare,
                    "$" + t.price);
        }
        System.out.println();
        System.out.println(
                "  How to apply: create these PriceTier, then assign the"
                        + " highest-priced sections to P1, mid sections to P2, etc.,");
        System.out.println(
                "  aiming for the seat shares above (exact section boundaries"
                        + " are yours to choose).");
    }

    // ── Comparables query ───────────────────────────────────────────────────

    private List<ComparablePerf> findComparables(
            String genre, int targetCapacity, String city) throws SQLException {
        String sql =
                "SELECT p.performance_id, e.name AS event_name, v.venue_id, v.name AS venue_name,"
                        + " pc.city,"
                        + " ("
                        + "   (SELECT COUNT(*) FROM Seat s"
                        + "    JOIN Section sec ON sec.venue_id = s.venue_id"
                        + "      AND sec.section_name = s.section_name"
                        + "    JOIN ReservedSection rs ON rs.venue_id = s.venue_id"
                        + "      AND rs.section_name = s.section_name"
                        + "    WHERE s.venue_id = v.venue_id)"
                        + "   +"
                        + "   (SELECT COALESCE(SUM(g.capacity),0) FROM GASection g"
                        + "    WHERE g.venue_id = v.venue_id)"
                        + " ) AS capacity"
                        + " FROM Performance p"
                        + " JOIN Event e ON e.event_id = p.event_id"
                        + " JOIN Venue v ON v.venue_id = p.venue_id"
                        + " JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " WHERE e.genre = ?"
                        + "   AND p.cancelled = FALSE"
                        + "   AND p.p_datetime >= DATE_SUB(NOW(), INTERVAL 24 MONTH)"
                        + "   AND p.p_datetime < NOW()"
                        + " HAVING capacity BETWEEN ? * 0.65 AND ? * 1.35"
                        + " ORDER BY (pc.city = ?) DESC, p.p_datetime DESC"
                        + " LIMIT 40";

        List<ComparablePerf> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, genre);
            ps.setInt(2, targetCapacity);
            ps.setInt(3, targetCapacity);
            ps.setString(4, city);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ComparablePerf c = new ComparablePerf();
                    c.performanceId = rs.getInt("performance_id");
                    c.eventName = rs.getString("event_name");
                    c.venueName = rs.getString("venue_name");
                    c.city = rs.getString("city");
                    c.capacity = rs.getInt("capacity");
                    c.sameCity = city != null && city.equalsIgnoreCase(c.city);
                    c.tiers = loadTierForPerf(c.performanceId);
                    if (!c.tiers.isEmpty()) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    private List<TierObs> loadTierForPerf(int performanceId) throws SQLException {
        String sql =
                "SELECT a.tier_name, pt.price,"
                        + " SUM(CASE WHEN rs.section_name IS NOT NULL THEN ("
                        + "   SELECT COUNT(*) FROM Seat s"
                        + "   WHERE s.venue_id = a.venue_id AND s.section_name = a.section_name"
                        + " ) ELSE COALESCE(ga.capacity,0) END) AS sellable,"
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

        List<TierObs> tiers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TierObs t = new TierObs();
                    t.tierName = rs.getString("tier_name");
                    t.price = rs.getBigDecimal("price").doubleValue();
                    t.sellable = rs.getInt("sellable");
                    t.sold = rs.getInt("sold");
                    tiers.add(t);
                }
            }
        }
        return tiers;
    }

    // ── Venue / tier helpers ────────────────────────────────────────────────

    private VenueCap loadVenue(int venueId) throws SQLException {
        String sql =
                "SELECT v.venue_id, v.name, pc.city,"
                        + " ("
                        + "   (SELECT COUNT(*) FROM Seat s WHERE s.venue_id = v.venue_id)"
                        + "   + (SELECT COALESCE(SUM(g.capacity),0) FROM GASection g"
                        + "      WHERE g.venue_id = v.venue_id)"
                        + " ) AS capacity"
                        + " FROM Venue v"
                        + " JOIN PostalCode pc ON pc.postal_code = v.postal_code"
                        + " WHERE v.venue_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, venueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                VenueCap v = new VenueCap();
                v.venueId = rs.getInt("venue_id");
                v.name = rs.getString("name");
                v.city = rs.getString("city");
                v.capacity = rs.getInt("capacity");
                return v;
            }
        }
    }

    private VenueCap venueOfPerformance(int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT venue_id FROM Performance WHERE performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Performance not found.");
                }
                VenueCap v = loadVenue(rs.getInt(1));
                if (v == null) {
                    throw new SQLException("Venue not found.");
                }
                return v;
            }
        }
    }

    private String genreOf(int performanceId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT e.genre FROM Performance p"
                                + " JOIN Event e ON e.event_id = p.event_id"
                                + " WHERE p.performance_id = ?")) {
            ps.setInt(1, performanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Performance not found.");
                }
                return rs.getString(1);
            }
        }
    }

    private TierState loadTierState(int performanceId, String tierName) throws SQLException {
        List<TierObs> tiers = loadTierForPerf(performanceId);
        for (TierObs t : tiers) {
            if (t.tierName.equals(tierName)) {
                TierState s = new TierState();
                s.tierName = t.tierName;
                s.price = t.price;
                s.sellable = t.sellable;
                s.sold = t.sold;
                return s;
            }
        }
        return null;
    }

    /** 1 = most expensive tier on the performance. */
    private int tierRank(int performanceId, String tierName) throws SQLException {
        List<TierObs> tiers = loadTierForPerf(performanceId);
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).tierName.equals(tierName)) {
                return i + 1;
            }
        }
        return 1;
    }

    // ── Math helpers ────────────────────────────────────────────────────────

    /** Ordinary least squares for sold = a − b·price. Returns [a, b]. */
    private static double[] fitLinearDemand(List<double[]> points) {
        int n = points.size();
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
        for (double[] p : points) {
            sumX += p[0];
            sumY += p[1];
            sumXX += p[0] * p[0];
            sumXY += p[0] * p[1];
        }
        double denom = n * sumXX - sumX * sumX;
        if (Math.abs(denom) < 1e-9) {
            return new double[] {sumY / n, 0};
        }
        double beta = (n * sumXY - sumX * sumY) / denom;
        double alpha = (sumY - beta * sumX) / n;
        if (beta <= 0) {
            return new double[] {alpha, -beta};
        }
        // Fit sloped the wrong way (sold rising with price); fall back to flat demand.
        return new double[] {sumY / n, 0};
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void normalize(double[] shares) {
        double s = 0;
        for (double x : shares) {
            s += x;
        }
        if (s <= 0) {
            for (int i = 0; i < shares.length; i++) {
                shares[i] = 1.0 / shares.length;
            }
            return;
        }
        for (int i = 0; i < shares.length; i++) {
            shares[i] /= s;
        }
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    private static final class VenueCap {
        int venueId;
        String name;
        String city;
        int capacity;
    }

    private static final class TierObs {
        String tierName;
        double price;
        int sellable;
        int sold;
    }

    private static final class ComparablePerf {
        int performanceId;
        String eventName;
        String venueName;
        String city;
        int capacity;
        boolean sameCity;
        List<TierObs> tiers = new ArrayList<>();
    }

    private static final class TierSuggestion {
        final String name;
        final double share;
        int seatShare;
        final BigDecimal price;

        TierSuggestion(String name, double share, int seatShare, BigDecimal price) {
            this.name = name;
            this.share = share;
            this.seatShare = seatShare;
            this.price = price;
        }
    }

    private static final class TierState {
        String tierName;
        double price;
        int sellable;
        int sold;
    }
}
