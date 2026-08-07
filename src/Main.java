import db.Database;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import service.BookingService;
import service.OrganizerService;
import service.QueryService;
import service.ReportService;
import service.ResaleService;
import service.ReviewService;
import service.UserService;
import util.Console;

/**
 * MyTix terminal application: operations, searches (Q1–Q7) and reports (R1–R9).
 *
 * <p>Compile / run from the project root:
 *
 * <pre>
 *   javac -cp mysql-connector-java-8.0.29.jar -d out src/*.java src/db/*.java src/util/*.java src/service/*.java
 *   java -cp "out;mysql-connector-java-8.0.29.jar" Main
 * </pre>
 */
public class Main {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Console ui;
    private final UserService users;
    private final OrganizerService organizers;
    private final BookingService bookings;
    private final ResaleService resales;
    private final ReviewService reviews;
    private final QueryService queries;
    private final ReportService reports;

    private Integer currentUserId;

    public Main(Connection conn, Console ui) {
        this.ui = ui;
        this.users = new UserService(conn);
        this.organizers = new OrganizerService(conn);
        this.bookings = new BookingService(conn);
        this.resales = new ResaleService(conn);
        this.reviews = new ReviewService(conn);
        this.queries = new QueryService(conn);
        this.reports = new ReportService(conn);
    }

    public static void main(String[] args) {
        try (Connection conn = Database.connect();
                Scanner scanner = new Scanner(System.in)) {
            System.out.println("Connected to MyTix (" + Database.URL + ")");
            new Main(conn, new Console(scanner)).run();
        } catch (SQLException e) {
            System.err.println("Could not connect to the database.");
            System.err.println(e.getMessage());
            System.err.println(
                    "Check that MySQL is running, ticketMaster_db exists, and"
                            + " credentials in src/db/Database.java are correct.");
        }
    }

    private void run() {
        boolean running = true;
        while (running) {
            printBanner();
            String choice = ui.line("Select: ");
            try {
                switch (choice) {
                    case "1" -> login();
                    case "2" -> createUser();
                    case "3" -> deleteUser();
                    case "4" -> organizerMenu();
                    case "5" -> customerMenu();
                    case "6" -> queryMenu();
                    case "7" -> reportMenu();
                    case "0" -> running = false;
                    default -> System.out.println("Unknown option.");
                }
            } catch (SQLException e) {
                System.out.println("\n  ERROR: " + friendly(e));
            } catch (IllegalArgumentException e) {
                System.out.println("\n  ERROR: " + e.getMessage());
            } catch (RuntimeException e) {
                System.out.println("\n  ERROR: " + e.getMessage());
            }
            if (running && !"0".equals(choice)) {
                ui.pause();
            }
        }
        System.out.println("Goodbye.");
    }

    private void printBanner() {
        System.out.println();
        System.out.println("========== MyTix ==========");
        if (currentUserId == null) {
            System.out.println("  (not signed in)");
        } else {
            try {
                String name = users.getName(currentUserId);
                String roles = "";
                if (users.isCustomer(currentUserId)) {
                    roles += "customer";
                }
                if (users.isOrganizer(currentUserId)) {
                    roles += (roles.isEmpty() ? "" : "+") + "organizer";
                }
                System.out.printf("  signed in as #%d %s [%s]%n", currentUserId, name, roles);
            } catch (SQLException e) {
                System.out.println("  signed in as #" + currentUserId);
            }
        }
        System.out.println("---------------------------");
        System.out.println("  1) Sign in (set current user id)");
        System.out.println("  2) Create user");
        System.out.println("  3) Delete user");
        System.out.println("  4) Organizer operations");
        System.out.println("  5) Customer operations");
        System.out.println("  6) Search queries (Q1–Q7)");
        System.out.println("  7) Reports (R1–R9)");
        System.out.println("  0) Exit");
        System.out.println("===========================");
    }

    // ── Auth / users ────────────────────────────────────────────────────────

    private void login() throws SQLException {
        int id = ui.intVal("User id: ");
        if (users.getName(id) == null) {
            System.out.println("  No such user.");
            return;
        }
        currentUserId = id;
        users.printUser(id);
    }

    private void createUser() throws SQLException {
        String name = ui.required("Name: ");
        String address = ui.required("Address: ");
        String email = ui.required("Email: ");
        LocalDate dob = ui.date("Date of birth");
        boolean customer = ui.yn("Register as customer?");
        boolean organizer = ui.yn("Register as organizer?");
        int id = users.createUser(name, address, email, dob, customer, organizer);
        System.out.println("  Created user #" + id);
        currentUserId = id;
    }

    private void deleteUser() throws SQLException {
        int id = ui.intVal("User id to delete: ");
        if (!ui.yn("Really delete user #" + id + "?")) {
            return;
        }
        users.deleteUser(id);
        System.out.println("  Deleted.");
        if (currentUserId != null && currentUserId == id) {
            currentUserId = null;
        }
    }

    private int requireSignedIn() {
        if (currentUserId == null) {
            throw new IllegalArgumentException("Sign in first (option 1).");
        }
        return currentUserId;
    }

    // ── Organizer menu ──────────────────────────────────────────────────────

    private void organizerMenu() throws SQLException {
        int uid = requireSignedIn();
        organizers.requireOrganizer(uid);

        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Organizer ---");
            System.out.println("  1) Create event");
            System.out.println("  2) Set event resale cap");
            System.out.println("  3) Add artist to event");
            System.out.println("  4) Add performance");
            System.out.println("  5) Add price tier");
            System.out.println("  6) Assign section to tier");
            System.out.println("  7) Update tier price");
            System.out.println("  8) Block seat");
            System.out.println("  9) Unblock seat");
            System.out.println(" 10) Cancel performance");
            System.out.println(" 11) List my events / genres / venues / artists");
            System.out.println("  0) Back");
            String c = ui.line("Select: ");
            try {
                switch (c) {
                    case "1" -> createEvent(uid);
                    case "2" -> setResaleCap(uid);
                    case "3" -> addArtist(uid);
                    case "4" -> addPerformance(uid);
                    case "5" -> addTier(uid);
                    case "6" -> assignSection(uid);
                    case "7" -> updateTierPrice(uid);
                    case "8" -> blockSeat(uid);
                    case "9" -> unblockSeat(uid);
                    case "10" -> cancelPerformance(uid);
                    case "11" -> browseOrganizer(uid);
                    case "0" -> back = true;
                    default -> System.out.println("Unknown option.");
                }
            } catch (SQLException e) {
                System.out.println("  ERROR: " + friendly(e));
            }
        }
    }

    private void createEvent(int uid) throws SQLException {
        System.out.println("Available genres:");
        organizers.listGenres();
        String name = ui.required("Event name: ");
        String genre = ui.required("Genre (exact): ");
        BigDecimal cap = ui.money("Resale cap percent (e.g. 120): ");
        int id = organizers.createEvent(uid, name, genre, cap);
        System.out.println("  Created event #" + id);
    }

    private void setResaleCap(int uid) throws SQLException {
        organizers.listOrganizerEvents(uid);
        int eventId = ui.intVal("Event id: ");
        BigDecimal cap = ui.money("New resale cap percent: ");
        organizers.setResaleCap(uid, eventId, cap);
        System.out.println("  Updated.");
    }

    private void addArtist(int uid) throws SQLException {
        organizers.listOrganizerEvents(uid);
        int eventId = ui.intVal("Event id: ");
        organizers.listArtists();
        int artistId = ui.intVal("Artist id: ");
        System.out.println("Billing order: headliner | special guest | opening act");
        String billing = ui.required("Billing order: ");
        organizers.addArtistToEvent(uid, eventId, artistId, billing);
        System.out.println("  Artist linked.");
    }

    private void addPerformance(int uid) throws SQLException {
        organizers.listOrganizerEvents(uid);
        int eventId = ui.intVal("Event id: ");
        organizers.listVenues();
        int venueId = ui.intVal("Venue id: ");
        LocalDateTime when = readDateTime("Performance date/time");
        int pid = organizers.addPerformance(uid, eventId, venueId, when);
        System.out.println("  Created performance #" + pid);
    }

    private void addTier(int uid) throws SQLException {
        int eventId = ui.intVal("Event id (to list performances): ");
        organizers.listPerformances(eventId);
        int perfId = ui.intVal("Performance id: ");
        String tier = ui.required("Tier name (e.g. P1): ");
        BigDecimal price = ui.money("Price: ");
        organizers.addPriceTier(uid, perfId, tier, price);
        System.out.println("  Tier added.");
    }

    private void assignSection(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        int venueId = ui.intVal("Venue id: ");
        organizers.listSections(venueId);
        String section = ui.required("Section name: ");
        String tier = ui.required("Tier name: ");
        organizers.assignSectionToTier(uid, perfId, venueId, section, tier);
        System.out.println("  Assigned.");
    }

    private void updateTierPrice(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        String tier = ui.required("Tier name: ");
        BigDecimal price = ui.money("New price: ");
        organizers.updateTierPrice(uid, perfId, tier, price);
        System.out.println("  Price updated.");
    }

    private void blockSeat(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        int venueId = ui.intVal("Venue id: ");
        String section = ui.required("Section: ");
        String row = ui.required("Row: ");
        int seat = ui.intVal("Seat number: ");
        String reason = ui.line("Reason (optional): ");
        if (reason.isEmpty()) {
            reason = null;
        }
        organizers.blockSeat(uid, perfId, venueId, section, row, seat, reason);
        System.out.println("  Seat blocked.");
    }

    private void unblockSeat(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        int venueId = ui.intVal("Venue id: ");
        String section = ui.required("Section: ");
        String row = ui.required("Row: ");
        int seat = ui.intVal("Seat number: ");
        organizers.unblockSeat(uid, perfId, venueId, section, row, seat);
        System.out.println("  Seat unblocked.");
    }

    private void cancelPerformance(int uid) throws SQLException {
        int eventId = ui.intVal("Event id: ");
        organizers.listPerformances(eventId);
        int perfId = ui.intVal("Performance id to cancel: ");
        if (!ui.yn("Cancel performance #" + perfId + " and refund all tickets?")) {
            return;
        }
        organizers.cancelPerformance(uid, perfId);
        System.out.println("  Performance cancelled.");
    }

    private void browseOrganizer(int uid) throws SQLException {
        System.out.println("My events:");
        organizers.listOrganizerEvents(uid);
        System.out.println("Genres:");
        organizers.listGenres();
        System.out.println("Venues:");
        organizers.listVenues();
        System.out.println("Artists:");
        organizers.listArtists();
        if (ui.yn("List performances for an event?")) {
            int eventId = ui.intVal("Event id: ");
            organizers.listPerformances(eventId);
        }
    }

    // ── Customer menu ───────────────────────────────────────────────────────

    private void customerMenu() throws SQLException {
        int uid = requireSignedIn();
        bookings.requireCustomer(uid);

        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Customer ---");
            System.out.println("  1) Book tickets");
            System.out.println("  2) List my orders");
            System.out.println("  3) List tickets on an order");
            System.out.println("  4) Cancel tickets on an order");
            System.out.println("  5) List tickets I currently own");
            System.out.println("  6) List a ticket for resale");
            System.out.println("  7) Withdraw a listing");
            System.out.println("  8) Browse / buy a listing");
            System.out.println("  9) Write a review");
            System.out.println("  0) Back");
            String c = ui.line("Select: ");
            try {
                switch (c) {
                    case "1" -> bookTickets(uid);
                    case "2" -> bookings.listCustomerOrders(uid);
                    case "3" -> {
                        int oid = ui.intVal("Order id: ");
                        bookings.listOrderTickets(uid, oid);
                    }
                    case "4" -> cancelTickets(uid);
                    case "5" -> bookings.listOwnedTickets(uid);
                    case "6" -> listForResale(uid);
                    case "7" -> withdrawListing(uid);
                    case "8" -> buyListing(uid);
                    case "9" -> writeReview(uid);
                    case "0" -> back = true;
                    default -> System.out.println("Unknown option.");
                }
            } catch (SQLException e) {
                System.out.println("  ERROR: " + friendly(e));
            }
        }
    }

    private void bookTickets(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        String card = ui.required("Card number: ");
        String expiry = ui.required("Card expiry (MM/YY): ");

        List<String[]> seats = new ArrayList<>();
        System.out.println("Add reserved seats (empty section name to stop).");
        while (true) {
            String section = ui.line("  Section: ");
            if (section.isEmpty()) {
                break;
            }
            String row = ui.required("  Row: ");
            int seat = ui.intVal("  Seat number: ");
            seats.add(new String[] {section, row, String.valueOf(seat)});
        }

        String gaSection = null;
        int gaQty = 0;
        if (ui.yn("Also buy general admission tickets?")) {
            gaSection = ui.required("GA section name: ");
            gaQty = ui.intVal("Quantity: ");
        }

        int orderId = bookings.book(uid, perfId, card, expiry, seats, gaSection, gaQty);
        System.out.println("  Order #" + orderId + " placed.");
    }

    private void cancelTickets(int uid) throws SQLException {
        bookings.listCustomerOrders(uid);
        int orderId = ui.intVal("Order id: ");
        bookings.listOrderTickets(uid, orderId);
        String csv = ui.required("Ticket ids to cancel (comma-separated): ");
        List<Integer> ids = bookings.parseTicketIdList(csv);
        bookings.cancelTickets(uid, orderId, ids);
    }

    private void listForResale(int uid) throws SQLException {
        bookings.listOwnedTickets(uid);
        int ticketId = ui.intVal("Ticket id: ");
        BigDecimal price = ui.money("List price: ");
        int listingId = resales.listForResale(uid, ticketId, price);
        System.out.println("  Created listing #" + listingId);
    }

    private void withdrawListing(int uid) throws SQLException {
        resales.listMyListings(uid);
        int listingId = ui.intVal("Listing id to withdraw: ");
        resales.withdrawListing(uid, listingId);
        System.out.println("  Withdrawn.");
    }

    private void buyListing(int uid) throws SQLException {
        resales.listActiveListings();
        int listingId = ui.intVal("Listing id to buy: ");
        if (!ui.yn("Purchase listing #" + listingId + "?")) {
            return;
        }
        resales.buyListing(uid, listingId);
    }

    private void writeReview(int uid) throws SQLException {
        int perfId = ui.intVal("Performance id you attended: ");
        int eventRating = ui.intVal("Event rating (1-5): ");
        int venueRating = ui.intVal("Venue rating (1-5): ");
        String comment = ui.required("Comment: ");
        int id = reviews.addReview(uid, perfId, eventRating, venueRating, comment);
        System.out.println("  Review #" + id + " saved.");
    }

    // ── Queries Q1–Q7 ───────────────────────────────────────────────────────

    private void queryMenu() throws SQLException {
        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Searches ---");
            System.out.println("  1) Q1  Nearby upcoming (lat/lng)");
            System.out.println("  2) Q2  By postal code (same + adjacent)");
            System.out.println("  3) Q3  Exact street address");
            System.out.println("  4) Q4  Date range + min available tickets");
            System.out.println("  5) Q5  Full filter search");
            System.out.println("  6) Q6  Seat-map summary");
            System.out.println("  7) Q7  Best available consecutive seats");
            System.out.println("  0) Back");
            String c = ui.line("Select: ");
            try {
                switch (c) {
                    case "1" -> runQ1();
                    case "2" -> runQ2();
                    case "3" -> runQ3();
                    case "4" -> runQ4();
                    case "5" -> runQ5();
                    case "6" -> {
                        int pid = ui.intVal("Performance id: ");
                        queries.q6SeatMap(pid);
                    }
                    case "7" -> runQ7();
                    case "0" -> back = true;
                    default -> System.out.println("Unknown option.");
                }
            } catch (SQLException e) {
                System.out.println("  ERROR: " + friendly(e));
            }
        }
    }

    private void runQ1() throws SQLException {
        double lat = ui.dbl("Latitude: ");
        double lon = ui.dbl("Longitude: ");
        String r = ui.line("Radius km [default " + QueryService.DEFAULT_RADIUS_KM + "]: ");
        double radiusKm =
                r.isEmpty() ? QueryService.DEFAULT_RADIUS_KM : Double.parseDouble(r);
        System.out.println("Rank by: distance | price_asc | price_desc");
        String rank = ui.line("Rank [distance]: ");
        if (rank.isEmpty()) {
            rank = "distance";
        }
        LocalDateTime[] range = optionalDateRange();
        Integer minAvail = ui.intOpt("Min available tickets (blank=any): ");
        queries.q1Nearby(lat, lon, radiusKm, rank, range[0], range[1], minAvail);
    }

    private void runQ2() throws SQLException {
        String postal = ui.required("Postal code: ");
        LocalDateTime[] range = optionalDateRange();
        Integer minAvail = ui.intOpt("Min available tickets (blank=any): ");
        queries.q2ByPostal(postal, range[0], range[1], minAvail);
    }

    private void runQ3() throws SQLException {
        String addr = ui.required("Exact street address: ");
        LocalDateTime[] range = optionalDateRange();
        Integer minAvail = ui.intOpt("Min available tickets (blank=any): ");
        queries.q3ByAddress(addr, range[0], range[1], minAvail);
    }

    private void runQ4() throws SQLException {
        System.out.println("Refine a base search with date range + min availability.");
        System.out.println("  Base: 1=nearby  2=postal  3=address  4=filter(city)");
        String base = ui.required("Base: ");
        LocalDate from = ui.date("From date");
        LocalDate to = ui.date("To date");
        int minAvail = ui.intVal("Minimum available tickets: ");
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);
        switch (base) {
            case "1" -> {
                double lat = ui.dbl("Latitude: ");
                double lon = ui.dbl("Longitude: ");
                queries.q1Nearby(
                        lat,
                        lon,
                        QueryService.DEFAULT_RADIUS_KM,
                        "distance",
                        fromDt,
                        toDt,
                        minAvail);
            }
            case "2" -> {
                String postal = ui.required("Postal code: ");
                queries.q2ByPostal(postal, fromDt, toDt, minAvail);
            }
            case "3" -> {
                String addr = ui.required("Exact street address: ");
                queries.q3ByAddress(addr, fromDt, toDt, minAvail);
            }
            default -> {
                String city = ui.required("City: ");
                queries.q5Filter(
                        city, null, null, fromDt, toDt, null, null, minAvail, null);
            }
        }
    }

    private void runQ5() throws SQLException {
        String city = ui.opt("City (blank=any): ");
        String segment = ui.opt("Segment (blank=any): ");
        String genre = ui.opt("Genre (blank=any): ");
        LocalDateTime[] range = optionalDateRange();
        BigDecimal minPrice = ui.moneyOpt("Min cheapest ticket (blank=any): ");
        BigDecimal maxPrice = ui.moneyOpt("Max cheapest ticket (blank=any): ");
        Integer minAvail = ui.intOpt("Min available tickets (blank=any): ");
        String seating = ui.opt("Seating type reserved|ga (blank=any): ");
        queries.q5Filter(
                city, segment, genre, range[0], range[1], minPrice, maxPrice, minAvail, seating);
    }

    private void runQ7() throws SQLException {
        int perfId = ui.intVal("Performance id: ");
        int q = ui.intVal("Number of consecutive seats: ");
        BigDecimal budget = ui.moneyOpt("Budget total (blank=no limit): ");
        queries.q7BestAvailable(perfId, q, budget);
    }

    private LocalDateTime[] optionalDateRange() {
        LocalDate from = ui.dateOpt("From date");
        LocalDate to = ui.dateOpt("To date");
        return new LocalDateTime[] {
            from == null ? null : from.atStartOfDay(),
            to == null ? null : to.atTime(23, 59, 59)
        };
    }

    // ── Reports R1–R9 ───────────────────────────────────────────────────────

    private void reportMenu() throws SQLException {
        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Reports ---");
            System.out.println("  1) R1  Revenue by city / by venue in city");
            System.out.println("  2) R2  Event & performance counts");
            System.out.println("  3) R3  Organizers ranked by gross revenue");
            System.out.println("  4) R4  Possible scalpers");
            System.out.println("  5) R5  Customers ranked by orders");
            System.out.println("  6) R6  Top cancellations");
            System.out.println("  7) R7  Sell-through");
            System.out.println("  8) R8  Resale report");
            System.out.println("  9) R9  Noun phrases per event");
            System.out.println("  0) Back");
            String c = ui.line("Select: ");
            try {
                switch (c) {
                    case "1" -> runR1();
                    case "2" -> runR2();
                    case "3" -> runR3();
                    case "4" -> reports.r4Scalpers();
                    case "5" -> runR5();
                    case "6" -> reports.r6TopCancellations();
                    case "7" -> runR7();
                    case "8" -> runR8();
                    case "9" -> {
                        Integer top = ui.intOpt("Top phrases per event [15]: ");
                        reports.r9NounPhrases(top == null ? 15 : top);
                    }
                    case "0" -> back = true;
                    default -> System.out.println("Unknown option.");
                }
            } catch (SQLException e) {
                System.out.println("  ERROR: " + friendly(e));
            }
        }
    }

    private void runR1() throws SQLException {
        LocalDate from = ui.date("From");
        LocalDate to = ui.date("To");
        System.out.println("  a) By city   b) By venue within a city");
        String which = ui.line("Select [a]: ");
        if ("b".equalsIgnoreCase(which)) {
            String city = ui.required("City: ");
            reports.r1RevenueByVenueInCity(city, from, to);
        } else {
            reports.r1RevenueByCity(from, to);
        }
    }

    private void runR2() throws SQLException {
        System.out.println(
                "  Grain: segment_genre | country | country_city | country_city_venue");
        String grain = ui.line("Grain [segment_genre]: ");
        if (grain.isEmpty()) {
            grain = "segment_genre";
        }
        reports.r2Counts(grain);
    }

    private void runR3() throws SQLException {
        String country = ui.opt("Country filter (blank=overall): ");
        String city = null;
        if (country != null) {
            city = ui.opt("City filter (blank=whole country): ");
        }
        reports.r3OrganizerRevenue(country, city);
    }

    private void runR5() throws SQLException {
        System.out.println("  a) By order count in a period");
        System.out.println("  b) By city (>=2 orders in past year)");
        String which = ui.line("Select [a]: ");
        if ("b".equalsIgnoreCase(which)) {
            reports.r5CustomersByOrdersPerCity();
        } else {
            LocalDate from = ui.date("From");
            LocalDate to = ui.date("To");
            reports.r5CustomersByOrders(from, to);
        }
    }

    private void runR7() throws SQLException {
        System.out.println("  a) All performances");
        System.out.println("  b) By price tier for one performance");
        System.out.println("  c) Sold-out / under-25% for a month by city");
        String which = ui.line("Select [a]: ");
        if ("b".equalsIgnoreCase(which)) {
            int pid = ui.intVal("Performance id: ");
            reports.r7SellThroughByTier(pid);
        } else if ("c".equalsIgnoreCase(which)) {
            int year = ui.intVal("Year: ");
            int month = ui.intVal("Month (1-12): ");
            reports.r7SoldOutAndWeakByMonth(year, month);
        } else {
            reports.r7SellThroughByPerformance();
        }
    }

    private void runR8() throws SQLException {
        LocalDate from = ui.date("From");
        LocalDate to = ui.date("To");
        System.out.println("  a) Per-event resale stats");
        System.out.println("  b) Top 10 events by resale volume");
        String which = ui.line("Select [a]: ");
        if ("b".equalsIgnoreCase(which)) {
            reports.r8Top10ResaleVolume(from, to);
        } else {
            reports.r8ResaleByEvent(from, to);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private LocalDateTime readDateTime(String label) {
        while (true) {
            String s = ui.line(label + " (YYYY-MM-DD HH:MM): ");
            try {
                return LocalDateTime.parse(s, DT);
            } catch (DateTimeParseException e) {
                System.out.println("  Invalid date/time.");
            }
        }
    }

    private static String friendly(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.toString();
        }
        // Surface trigger SIGNAL messages cleanly.
        int idx = msg.indexOf("A user must be");
        if (idx < 0) {
            idx = msg.indexOf("Seat is");
        }
        if (idx < 0) {
            idx = msg.indexOf("Cannot block");
        }
        if (idx < 0) {
            idx = msg.indexOf("General admission");
        }
        if (idx < 0) {
            idx = msg.indexOf("Listing price exceeds");
        }
        if (idx < 0) {
            idx = msg.indexOf("Section does not");
        }
        if (idx >= 0) {
            int end = msg.indexOf('\n', idx);
            return end > idx ? msg.substring(idx, end).trim() : msg.substring(idx).trim();
        }
        return msg;
    }
}
