# MyTix User Manual

**Course:** CSCC43 Database Design Project  
**System:** MyTix (event ticketing platform)  
**Group members:** *(fill in names and student numbers)*

---

## 1. Purpose of this document

This manual explains how to install, load, and run MyTix; how to use each part of the text interface; what the sample data supports; and the known limitations of the system together with ideas for improvement.

The application is a single-user terminal program that talks to a MySQL 8 database through JDBC embedded SQL.

---

## 2. Requirements

Before you begin, ensure the following are available:

| Component | Notes |
|-----------|--------|
| MySQL 8 | Server running locally; client able to execute `.sql` files |
| Java JDK 17+ | `javac` and `java` on your `PATH` |
| MySQL Connector/J | Bundled as `mysql-connector-java-8.0.29.jar` in the project root |
| Bash (for `run.sh`) | Linux / macOS / Git Bash on Windows |

Default JDBC settings assume a local MySQL 8 server with the `root` account
and no password (the standard result of the course-provided MySQL install
instructions):

- URL: `jdbc:mysql://localhost:3306/ticketMaster_db`
- User: `root`
- Password: *(empty string)*

If your local setup differs, override these without editing any source file
by setting environment variables before running `run.sh`:

```bash
export MYTIX_DB_URL="jdbc:mysql://localhost:3306/ticketMaster_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export MYTIX_DB_USER="root"
export MYTIX_DB_PASSWORD="yourpassword"
./run.sh
```

---

## 3. Installation and first-time setup

The grading sequence is: create the schema, load sample data, then run the application. Do the same locally.

### 3.1 Create the database and schema

From the project root (paths assume the MySQL client’s working directory is the project root, or use absolute paths as in `sql/load.sql`):

```bash
mysql -u root -e "CREATE DATABASE IF NOT EXISTS ticketMaster_db;"
mysql -u root ticketMaster_db < sql/schema.sql
```

If your MySQL root account has a password, add `-p` and enter it when prompted.

`sql/schema.sql` creates all tables, indexes, check constraints, and triggers.

To wipe the schema and start over:

```bash
mysql -u root ticketMaster_db < sql/drop.sql
```

### 3.2 Load sample data

Bulk load requires `LOCAL INFILE`:

```bash
mysql --local-infile=1 -u root ticketMaster_db < sql/load.sql
```

`sql/load.sql` uses paths relative to the project root (e.g. `data/Genre.csv`), so run it from the project root as shown above.

**MySQL Workbench:** enable local infile on the connection (`OPT_LOCAL_INFILE=1` under connection Advanced options). Workbench resolves relative `LOCAL INFILE` paths against wherever the Workbench process was launched from, not the project root, so the CLI command above is the reliable option for grading.

**Regenerating CSVs** (optional):

```bash
python generate_data.py
```

### 3.3 Run the application

**Linux / macOS / Git Bash:**

```bash
./run.sh
```

`run.sh` compiles all sources under `src/` into `out/` and starts `Main`.

On success you should see:

```text
Connected to MyTix (...)
========== MyTix ==========
```

---

## 4. Sample data cheat sheet

The loaded dataset is sized to exercise every operation, query, and report. Approximate contents:

| Entity | Scale (approx.) |
|--------|------------------|
| Venues | 9 (Toronto, Vancouver, Montreal, New York, Chicago) |
| Organizers | users **1–5** |
| Customers | users **6–115** |
| Events / performances | 25+ / 60+ |
| Orders / tickets | hundreds / thousands |

Useful landmarks for manual testing:

- **Organizer sign-in:** user id `1` (or any of 1–5).
- **Customer sign-in:** user id `6` or higher.
- **Nearby search (Q1):** Toronto venues sit near latitude `43.64`, longitude `-79.38`.
- **Postal search (Q2):** try `M5V 3L9` (Toronto cluster) or `10001` (New York).
- **Exact address (Q3):** e.g. `40 Bay Street` (Scotiabank Arena).
- **Upcoming performance with consecutive free seats:** use the seat-map (Q6) and best-available (Q7) on an upcoming performance at a large venue (e.g. venue 1).
- **Scalpers (R4):** customers `6` and `7` were generated to list more than half of ≥10 purchased tickets.

“Today” for the generated calendar is treated as **2026-08-07** (past vs upcoming performances are relative to that design date and to `NOW()` when you run queries).

---

## 5. Using the application

The main menu appears after you connect. Many actions require you to **sign in** first (option 1) with a numeric user id. The signed-in user is shown at the top of the menu.

After most actions, press **Enter** to continue.

### 5.1 Main menu

| Option | Action |
|--------|--------|
| 1 | Sign in (set current user id) |
| 2 | Create user (customer and/or organizer) |
| 3 | Delete user |
| 4 | Organizer operations |
| 5 | Customer operations |
| 6 | Search queries (Q1–Q7) |
| 7 | Reports (R1–R9) |
| 0 | Exit |

### 5.2 Users

**Create user**  
Collects name, address, email, date of birth (`YYYY-MM-DD`), and whether the account is a customer, an organizer, or both. The database rejects users under 18. Email must be unique.

**Sign in**  
Enter an existing `user_id`. No password is used (see Limitations).

**Delete user**  
Removes the account if the database allows it. Users with orders, listings, resales, or managed events cannot be deleted (foreign keys preserve history). Reviews belonging to the user are removed with the account.

### 5.3 Organizer operations

Sign in as an organizer, then choose main menu **4**.

| Option | What it does |
|--------|----------------|
| 1 | Create an event (name, genre, resale cap %) |
| 2 | Update an event’s resale cap |
| 3 | Link an artist to an event with a billing order (`headliner`, `special guest`, `opening act`) |
| 4 | Add a performance (event, venue, date/time `YYYY-MM-DD HH:MM`) |
| 5 | Add a price tier to a performance |
| 6 | Assign a venue section to a tier for a performance |
| 7 | Update a tier’s price (future performances only; refused if any ticket already sold in that tier) |
| 8 / 9 | Block / unblock a reserved seat for a performance |
| 10 | Cancel a performance (refunds all sold tickets; withdraws active listings) |
| 11 | Browse your events, genres, venues, artists, and performances |
| 12 | Organizer toolkit (see §5.6) |
| 0 | Back to main menu |

**Typical setup order for a new show:** create event → add artist(s) → add performance → add price tiers → assign every section to a tier → (optional) block seats.

### 5.4 Customer operations

Sign in as a customer, then choose main menu **5**.

| Option | What it does |
|--------|----------------|
| 1 | Book tickets for a performance (reserved seats and/or GA quantity; card number + expiry stored on the order) |
| 2 | List your orders |
| 3 | List tickets on one of your orders |
| 4 | Cancel selected tickets on an order you placed (only if the performance is **more than seven days** away) |
| 5 | List tickets you currently own (original purchase or latest resale) |
| 6 | List an owned ticket for resale (price must not exceed the event resale cap) |
| 7 | Withdraw one of your active listings |
| 8 | Browse active listings and buy one (ownership transfers) |
| 9 | Write a review for a past performance you attended |
| 0 | Back |

**Booking reserved seats:** enter section, row, and seat number repeatedly; leave section blank to finish, then optionally add GA tickets.

**Cancellation** may only be done by the customer who placed the order. Cancelled seats return to inventory.

### 5.5 Search queries (Q1–Q7)

Main menu **6**. Searches do not require sign-in.

| Query | Description |
|-------|-------------|
| Q1 | Upcoming performances within a radius (km) of a latitude/longitude. Default radius **50 km**. Rank by `distance`, `price_asc`, or `price_desc`. Optional date bounds and minimum available tickets. |
| Q2 | Upcoming performances at venues in the same or adjacent postal codes. Adjacent = shared first three characters of the compact postal code, or the same first two characters within the same city. |
| Q3 | Exact street-address lookup of a venue and its upcoming performances. |
| Q4 | Temporal refinement: choose a base search (nearby / postal / address / city) plus a required date range and minimum available tickets. |
| Q5 | Full filter: any combination of city, segment, genre, date range, cheapest-ticket price band, minimum availability, and seating type (`reserved` / `ga`). |
| Q6 | Seat-map summary for one performance: per section, tier, price, available / sold / blocked counts (GA reports remaining capacity). |
| Q7 | Best available: cheapest block of `q` consecutive seats in the same row, optional total budget. |

Distance in Q1 is great-circle distance in **kilometres** (Haversine) from the coordinates you enter to each venue’s stored latitude/longitude.

### 5.6 Reports (R1–R9)

Main menu **7**.

| Report | Description |
|--------|-------------|
| R1 | Tickets sold and gross revenue in a date range, by city, or by venue within a city. |
| R2 | Counts of events and performances by segment/genre, country, country+city, or country+city+venue. |
| R3 | Organizers ranked by gross revenue (optional country / city filters). |
| R4 | Possible scalpers: customers who, in the past year and by city, bought ≥10 tickets and listed more than half of them. |
| R5 | Customers ranked by number of orders in a period; or by city with ≥2 orders in the past year. |
| R6 | Customers with the most customer-initiated ticket cancellations, and organizers with the most cancelled performances, in the past year. |
| R7 | Sell-through rate per performance; per price tier for one performance; or sold-out / under-25%-capacity performances for a given month by city. |
| R8 | Per-event resale stats (completed resales, average markup ratio, fraction listed at the cap); or top 10 events by resale volume in a period. |
| R9 | Most frequent noun phrases in review comments for each event (lightweight token / bigram / trigram extraction in Java). |

Gross revenue in these reports is based on **face value** of non-cancelled tickets.

### 5.7 Organizer toolkit

Organizer menu **12**.

1. **Suggest tiers & prices for a new performance**  
   Enter a venue id and genre. The toolkit finds comparable past performances (same genre, venue capacity within ±35%, last 24 months; same-city comps weighted more heavily) and suggests:
   - how many tiers to define,
   - what share of capacity each tier should get,
   - a price for each tier.

2. **Estimate revenue impact of a tier price change**  
   Enter a performance, tier name, and proposed price. The toolkit fits a simple linear demand model from comparable tier observations (or a fallback price elasticity) and prints an estimated change in revenue. This is a positioning aid, not a guarantee.

---

## 6. Business rules the system enforces

| Rule | Where enforced |
|------|----------------|
| User must be at least 18 | Database trigger |
| Seat not double-sold / not sold while blocked | Database trigger |
| Sold seat cannot be blocked | Database trigger |
| GA sales within section capacity | Database trigger |
| Listing price ≤ event resale cap | Database trigger (+ app check) |
| Section assigned only for the performance’s venue | Database trigger |
| Tier price change only if future and no sales in that tier | Application |
| Customer cancel only ≥7 days before performance | Application |
| Only order owner cancels tickets; only event organizer cancels a performance | Application |
| Only current ticket owner may list / withdraw | Application |
| Review only after attending a past performance; at most once per performance | Application + unique constraint |

---

## 7. System limitations

1. **No authentication.** Sign-in is by numeric user id only; there are no passwords or sessions.
2. **No real payments.** Card numbers are stored on orders for the assignment only; nothing is charged or validated.
3. **Text UI only.** There is no graphical seat map; seats are entered as section / row / number.
4. **Single-user client.** Concurrent double-booking is prevented by database triggers for this terminal app; a multi-user deployment would need stronger locking around booking.
5. **User delete is strict.** Accounts with order or resale history (or managed events) cannot be removed, by design, so history is preserved.
6. **Ownership is derived**, not stored as a mutable column. Current owner = latest resale buyer, else original order customer.
7. **Adjacent postal codes** are approximate (prefix rules), not true geographic adjacency polygons.
8. **Q1 “today”** uses the database server’s `NOW()`; if the sample calendar and the machine clock differ a lot, “upcoming” vs “past” sets may look unexpected.
9. **Organizer toolkit** suggestions depend on having enough comparable history; sparse genres fall back to simple heuristics.
10. **R9 noun phrases** use stopword filtering and n-grams, not a full NLP parser, so phrases are approximate.
11. **`sql/load.sql` paths** are relative to the MySQL client's working directory, so `mysql ... < sql/load.sql` must be run from the project root (this is how the grading sequence invokes it).
12. **Windows:** PowerShell does not execute `./run.sh` natively; use Git Bash or WSL with a JDK.

---

## 8. Possible improvements

1. Proper login (hashed passwords) and role-based menus without typing raw user ids.
2. Interactive seat-map display for a performance (ASCII or web UI) driven by Q6.
3. Transaction isolation / `SELECT … FOR UPDATE` on booking for true multi-user safety.
4. A dedicated ownership-history table if auditors want a first-class log beyond orders + resales.
5. Richer postal adjacency (stored neighbour pairs or geospatial types) for Q2.
6. Payment tokenization and PCI-aware handling if the system were productionized.
7. Stronger demand model in the organizer toolkit (seasonality, day-of-week, artist popularity).
8. Full NLP pipeline (or a library such as OpenNLP) for R9 noun-phrase quality.
9. Batch admin tools: import venues/sections from files, clone a performance’s tier layout.
10. Export reports to CSV from the menu for marking and organizer use.

---

*End of MyTix user manual.*
