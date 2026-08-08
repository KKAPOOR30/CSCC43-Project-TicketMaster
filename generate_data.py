#!/usr/bin/env python3
"""
Generate sample CSV data for MyTix that satisfies every minimum in the
project description. Run from the project root:

    python generate_data.py

Writes files into ./data/. "Today" for the dataset is 2026-08-07.
"""

from __future__ import annotations

import csv
import os
import random
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
TODAY = date(2026, 8, 7)
RNG = random.Random(42)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sql_null(value) -> str:
    """Empty / None becomes MySQL LOAD DATA null marker \\N."""
    if value is None or value == "":
        return r"\N"
    return value


def write_csv(name: str, headers: list[str], rows: list[list]) -> None:
    path = DATA / name
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(headers)
        for row in rows:
            w.writerow([sql_null(c) for c in row])
    print(f"  wrote {name} ({len(rows)} rows)")


def dt(d: date, hour: int = 19, minute: int = 0) -> str:
    return datetime(d.year, d.month, d.day, hour, minute, 0).strftime("%Y-%m-%d %H:%M:%S")


def fake_card() -> str:
    return "".join(str(RNG.randint(0, 9)) for _ in range(16))


def fake_expiry() -> str:
    return f"{RNG.randint(1, 12):02d}/{RNG.randint(27, 30)}"


def days_ago(n: int) -> date:
    return TODAY - timedelta(days=n)


def days_ahead(n: int) -> date:
    return TODAY + timedelta(days=n)


# ---------------------------------------------------------------------------
# Reference content
# ---------------------------------------------------------------------------

GENRES = [
    # (genre, segment) — Ticketmaster-style taxonomy
    ("Rock", "Music"),
    ("Pop", "Music"),
    ("Hip-Hop/Rap", "Music"),
    ("Musical", "Arts & Theatre"),
    ("Comedy", "Arts & Theatre"),
    ("Basketball", "Sports"),
    ("Hockey", "Sports"),
    ("Classical", "Music"),
]

ARTIST_NAMES = [
    "Aurora Vale", "Neon Harbor", "The Midnight Relay", "Lila Crow",
    "Broken Compass", "DJ Cascade", "Iron Lantern", "Sofia Maren",
    "The Hollow Keys", "Marcus Quinn", "Velvet Static", "Redline United",
    "Northern Pulse", "Echo Chamber", "Kai Rivera", "Stage Left Collective",
    "Blue Orchard", "Thunder Bay FC", "Metro Philharmonic", "Clara Voss",
]

FIRST = [
    "Alex", "Jordan", "Sam", "Taylor", "Morgan", "Casey", "Riley", "Avery",
    "Quinn", "Jamie", "Cameron", "Drew", "Parker", "Reese", "Skyler", "Blake",
    "Harper", "Rowan", "Finley", "Emerson", "Noah", "Olivia", "Liam", "Emma",
    "Ethan", "Sophia", "Mason", "Isabella", "Lucas", "Mia", "Logan", "Charlotte",
]
LAST = [
    "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
    "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
    "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
    "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez",
]

EVENT_NAMES = [
    "City Lights Tour", "Midnight Sessions", "Arena Rock Night",
    "Summer Pop Fest", "Rap Revolution", "Broadway Bound",
    "Laugh Out Loud Live", "Court Kings Showcase", "Ice Rivalry",
    "Symphony Under Stars", "Indie Rising", "Electric Dreams",
    "Folk & Fire", "Comedy Cellar Night", "Hoops Classic",
    "Rinkside Rivals", "Chamber Series", "Stadium Anthems",
    "Theatre Nights: Les Amis", "World Beat Week",
    "Acoustic After Dark", "Neon Wave Tour", "Playoff Fever",
    "Opera Gala", "Open Mic Titans",
]

REVIEW_TEMPLATES = [
    "The {adj} performance had incredible energy from start to finish. "
    "The {noun1} was outstanding and the {noun2} made the night unforgettable. "
    "I would gladly see this {noun3} again.",
    "What a {adj} evening at the venue. The {noun1} captivated the crowd "
    "while the {noun2} added depth to every song. Truly a memorable {noun3}.",
    "Solid {noun3} overall with a few rough edges. The {noun1} stood out "
    "and the {noun2} kept things interesting throughout the second half.",
    "An unforgettable night. The {adj} {noun1} and powerful {noun2} "
    "created a {noun3} worth every dollar of the ticket price.",
    "Loved the atmosphere. The {noun1} was crisp, the {noun2} was emotional, "
    "and the entire {noun3} felt carefully crafted for the audience.",
]
ADJS = ["electrifying", "intimate", "spectacular", "raw", "polished", "vibrant"]
NOUNS = [
    "vocals", "lighting", "guitar solo", "choreography", "encore", "setlist",
    "bass line", "crowd", "stage design", "drumming", "harmony", "finale",
    "opening act", "headliner", "sound mix", "performance", "show", "concert",
]


# ---------------------------------------------------------------------------
# Build entities
# ---------------------------------------------------------------------------

def main() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    print("Generating MyTix sample data...")

    # ----- Genres -----
    genre_rows = [[g, s] for g, s in GENRES]
    write_csv("Genre.csv", ["genre", "segment"], genre_rows)

    # ----- Postal codes (4+ cities, 2 countries; adjacent clusters) -----
    # Adjacent = same first 3 characters (FSA / ZIP prefix) for Q2.
    postal_rows = [
        # Toronto cluster (adjacent: M5V / M5H / M5J)
        ["M5V 3L9", "Toronto", "Canada"],
        ["M5H 2N2", "Toronto", "Canada"],
        ["M5J 2X1", "Toronto", "Canada"],
        ["M4W 1A8", "Toronto", "Canada"],
        # Vancouver
        ["V6B 4Y8", "Vancouver", "Canada"],
        ["V5K 0A1", "Vancouver", "Canada"],
        # Montreal
        ["H2X 1Y4", "Montreal", "Canada"],
        ["H3B 2Y5", "Montreal", "Canada"],
        # New York (US)
        ["10001", "New York", "United States"],
        ["10002", "New York", "United States"],
        ["10003", "New York", "United States"],
        # Chicago
        ["60601", "Chicago", "United States"],
        ["60602", "Chicago", "United States"],
    ]
    write_csv("PostalCode.csv", ["postal_code", "city", "country"], postal_rows)

    # ----- Venues (8+, varied size; 3 close in Toronto; 2+ with GA+reserved) -----
    # venue_id, name, lat, lon, street, postal, size_hint
    venues_meta = [
        (1, "Scotiabank Arena", 43.643500, -79.379100, "40 Bay Street", "M5V 3L9", "large"),
        (2, "Roy Thomson Hall", 43.646600, -79.386300, "60 Simcoe Street", "M5H 2N2", "medium"),
        (3, "Harbourfront Centre Theatre", 43.638900, -79.382000, "235 Queens Quay West", "M5J 2X1", "small"),
        (4, "Massey Hall", 43.654200, -79.379000, "178 Victoria Street", "M4W 1A8", "medium"),
        (5, "Rogers Arena", 49.277800, -123.108900, "800 Griffiths Way", "V6B 4Y8", "large"),
        (6, "Place des Arts", 45.508800, -73.566500, "175 Sainte-Catherine Street West", "H2X 1Y4", "medium"),
        (7, "Madison Square Garden", 40.750500, -73.993400, "4 Pennsylvania Plaza", "10001", "large"),
        (8, "United Center", 41.880700, -87.674200, "1901 West Madison Street", "60601", "large"),
        (9, "The Opera House Toronto", 43.666100, -79.349400, "735 Queen Street East", "M4W 1A8", "small"),
    ]
    venue_rows = [[v[0], v[1], f"{v[2]:.6f}", f"{v[3]:.6f}", v[4], v[5]] for v in venues_meta]
    write_csv(
        "Venue.csv",
        ["venue_id", "name", "latitude", "longitude", "street_address", "postal_code"],
        venue_rows,
    )

    # Section layouts per size
    # reserved: list of (section_name, rows, seats_per_row)
    # ga: list of (section_name, capacity) — only for some venues
    layouts = {
        "small": {
            "reserved": [("Floor", ["A", "B", "C", "D"], 8), ("Balcony", ["E", "F"], 6)],
            "ga": [("General Admission", 40)],
        },
        "medium": {
            "reserved": [
                ("Orchestra", ["A", "B", "C", "D"], 10),
                ("Mezzanine", ["F", "G"], 8),
                ("Balcony", ["J", "K"], 8),
            ],
            "ga": [],
        },
        "large": {
            "reserved": [
                ("Floor", ["A", "B", "C", "D"], 10),
                ("100 Level", ["101", "102", "103"], 8),
                ("200 Level", ["201", "202"], 8),
            ],
            "ga": [("Standing Pit", 50)],
        },
    }

    section_rows = []
    reserved_rows = []
    ga_rows = []
    seatrow_rows = []
    seat_rows = []
    # seat index: (venue_id, section, row, seat) for later booking
    all_seats: dict[int, list[tuple[str, str, int]]] = defaultdict(list)
    ga_capacity: dict[tuple[int, str], int] = {}

    for vid, _, _, _, _, _, size in venues_meta:
        layout = layouts[size]
        for sec, rows, nseats in layout["reserved"]:
            section_rows.append([vid, sec])
            reserved_rows.append([vid, sec])
            for rn in rows:
                seatrow_rows.append([vid, sec, rn])
                for sn in range(1, nseats + 1):
                    seat_rows.append([vid, sec, rn, sn])
                    all_seats[vid].append((sec, rn, sn))
        for sec, cap in layout["ga"]:
            section_rows.append([vid, sec])
            ga_rows.append([vid, sec, cap])
            ga_capacity[(vid, sec)] = cap

    write_csv("Section.csv", ["venue_id", "section_name"], section_rows)
    write_csv("ReservedSection.csv", ["venue_id", "section_name"], reserved_rows)
    write_csv("GASection.csv", ["venue_id", "section_name", "capacity"], ga_rows)
    write_csv("SeatRow.csv", ["venue_id", "section_name", "row_name"], seatrow_rows)
    write_csv("Seat.csv", ["venue_id", "section_name", "row_name", "seat_number"], seat_rows)

    # ----- Users: 5 organizers + 110 customers -----
    user_rows = []
    customer_rows = []
    organizer_rows = []

    # Organizers 1..5
    org_names = [
        "LiveNation North", "StageWorks Inc", "Arena Events Co",
        "Broadway Partners", "Stadium Presents",
    ]
    for i, name in enumerate(org_names, start=1):
        user_rows.append([
            i, name, f"{100 + i} King Street West, Toronto",
            f"organizer{i}@mytix.example", "1980-05-15",
        ])
        organizer_rows.append([i])

    # Customers 6..115
    for i in range(6, 116):
        fn = FIRST[(i - 6) % len(FIRST)]
        ln = LAST[(i * 3) % len(LAST)]
        name = f"{fn} {ln}"
        city_addr = [
            "12 Queen Street East, Toronto",
            "88 Robson Street, Vancouver",
            "200 Sainte-Catherine, Montreal",
            "350 5th Avenue, New York",
            "233 S Wacker Drive, Chicago",
        ][(i - 6) % 5]
        dob_year = RNG.randint(1970, 2006)  # all >= 18 as of 2026-08-07
        dob = date(dob_year, RNG.randint(1, 12), RNG.randint(1, 28))
        user_rows.append([
            i, name, city_addr,
            f"customer{i}@mail.example", dob.isoformat(),
        ])
        customer_rows.append([i])

    write_csv(
        "User.csv",
        ["user_id", "name", "address", "email", "date_of_birth"],
        user_rows,
    )
    write_csv("Customer.csv", ["user_id"], customer_rows)
    write_csv("Organizer.csv", ["user_id"], organizer_rows)

    # ----- Artists -----
    artist_rows = [[i, n] for i, n in enumerate(ARTIST_NAMES, start=1)]
    write_csv("Artist.csv", ["artist_id", "name"], artist_rows)

    # ----- Events (20+) -----
    # event_id, name, genre, resale_cap_pct, organizer_user_id
    event_rows = []
    features_rows = []
    for i, ename in enumerate(EVENT_NAMES, start=1):
        genre = GENRES[(i - 1) % len(GENRES)][0]
        org = ((i - 1) % 5) + 1
        cap = 120.00 if i % 4 != 0 else 150.00
        event_rows.append([i, ename, genre, f"{cap:.2f}", org])
        # headliner
        a1 = ((i - 1) % len(ARTIST_NAMES)) + 1
        features_rows.append([i, a1, "headliner"])
        # some multi-artist
        if i % 3 == 0:
            a2 = ((i + 4) % len(ARTIST_NAMES)) + 1
            features_rows.append([i, a2, "special guest"])
        if i % 5 == 0:
            a3 = ((i + 8) % len(ARTIST_NAMES)) + 1
            features_rows.append([i, a3, "opening act"])

    write_csv(
        "Event.csv",
        ["event_id", "name", "genre", "resale_cap_pct", "user_id"],
        event_rows,
    )
    write_csv(
        "Features.csv",
        ["event_id", "artist_id", "billing_order"],
        features_rows,
    )

    # ----- Performances (60+) -----
    # Mix: past, upcoming, cancelled, tour, residency
    performance_rows = []
    # pid -> meta
    perf_meta: dict[int, dict] = {}

    pid = 0

    def add_perf(event_id: int, venue_id: int, when: date, hour: int = 19,
                 cancelled: bool = False, cancelled_at: str | None = None) -> int:
        nonlocal pid
        pid += 1
        performance_rows.append([
            pid,
            dt(when, hour),
            1 if cancelled else 0,
            cancelled_at if cancelled else "",
            event_id,
            venue_id,
        ])
        perf_meta[pid] = {
            "event_id": event_id,
            "venue_id": venue_id,
            "when": when,
            "cancelled": cancelled,
            "datetime": datetime(when.year, when.month, when.day, hour, 0, 0),
        }
        return pid

    # Theatre residency: Event 19 at venue 2 — many performances
    for k in range(12):
        add_perf(19, 2, days_ago(200 - k * 7), 20)

    # Tour: Event 1 at several venues
    tour_dates = [days_ago(90), days_ago(80), days_ago(70), days_ahead(20), days_ahead(35), days_ahead(50)]
    tour_venues = [1, 5, 7, 1, 8, 6]
    for d, v in zip(tour_dates, tour_venues):
        add_perf(1, v, d)

    # Cancelled performances (at least 2 in past year) — organizers cancel
    add_perf(3, 4, days_ago(60), cancelled=True, cancelled_at=dt(days_ago(65), 10))
    add_perf(7, 7, days_ago(30), cancelled=True, cancelled_at=dt(days_ago(35), 12))

    # Past sold-out candidates and low-sell candidates across cities/months
    past_specs = [
        # (event, venue, days_ago, tag)
        (2, 3, 40, "soldout"),
        (4, 9, 55, "soldout"),
        (5, 3, 100, "soldout"),
        (6, 6, 120, "low"),
        (8, 8, 140, "low"),
        (10, 5, 45, "low"),
        (11, 1, 75, "normal"),
        (12, 5, 85, "normal"),
        (13, 2, 95, "normal"),
        (14, 7, 110, "normal"),
        (15, 4, 25, "normal"),
        (16, 8, 15, "normal"),
        (17, 6, 5, "normal"),
        (18, 1, 10, "normal"),
        (20, 3, 8, "normal"),
        (21, 9, 12, "normal"),
        (22, 5, 18, "normal"),
        (23, 7, 22, "normal"),
        (24, 2, 28, "normal"),
        (25, 8, 33, "normal"),
    ]
    past_tags: dict[int, str] = {}
    for ev, ven, ago, tag in past_specs:
        p = add_perf(ev, ven, days_ago(ago))
        past_tags[p] = tag

    # Upcoming: plenty of availability
    # Special upcoming >7 days: venue 1, with consecutive seats scenario
    upcoming_demo = add_perf(2, 1, days_ahead(21), 19)  # >7 days
    upcoming_near = add_perf(4, 1, days_ahead(3), 20)   # <7 days with sold tickets
    upcoming_price_change = add_perf(5, 5, days_ahead(40))  # tiers with no sales
    upcoming_price_locked = add_perf(6, 7, days_ahead(25))  # will have sales

    # More upcoming to reach 60+
    more_upcoming = [
        (8, 3, 14), (9, 4, 18), (10, 6, 22), (11, 8, 28),
        (12, 9, 32), (13, 2, 38), (14, 5, 42), (15, 1, 45),
        (16, 7, 48), (17, 6, 55), (18, 8, 60), (20, 4, 65),
        (21, 3, 70), (22, 1, 75), (23, 5, 80), (24, 7, 85),
        (25, 2, 90), (3, 9, 16), (7, 4, 24), (9, 1, 30),
        (11, 5, 36), (13, 7, 44), (15, 6, 52), (17, 8, 58),
    ]
    for ev, ven, ahead in more_upcoming:
        add_perf(ev, ven, days_ahead(ahead))

    write_csv(
        "Performance.csv",
        ["performance_id", "p_datetime", "cancelled", "cancelled_at", "event_id", "venue_id"],
        performance_rows,
    )
    print(f"  (performances total: {len(performance_rows)})")

    # ----- Price tiers & section assignments -----
    tier_rows = []
    assign_rows = []

    # For each performance, create at least 2 tiers and assign every section
    for p_id, meta in perf_meta.items():
        vid = meta["venue_id"]
        size = next(v[6] for v in venues_meta if v[0] == vid)
        layout = layouts[size]
        reserved_secs = [s[0] for s in layout["reserved"]]
        ga_secs = [s[0] for s in layout["ga"]]

        # Vary prices; special case for venue 1 with two perfs different assignments
        base = 50 + (p_id % 7) * 10
        # upcoming_demo and another perf at venue 1 get different assignments
        if p_id == upcoming_demo:
            tiers = [("P1", base + 130), ("P2", base + 70), ("P3", base + 20)]
            mapping = {}
            for i, s in enumerate(reserved_secs):
                mapping[s] = tiers[min(i, 2)][0]
            for s in ga_secs:
                mapping[s] = "P3"
        elif meta["venue_id"] == 1 and meta["event_id"] == 1 and not meta["cancelled"]:
            # Different tier structure for same venue
            tiers = [("VIP", base + 200), ("Standard", base + 80)]
            mapping = {reserved_secs[0]: "VIP"}
            for s in reserved_secs[1:]:
                mapping[s] = "Standard"
            for s in ga_secs:
                mapping[s] = "Standard"
        else:
            tiers = [("P1", base + 100), ("P2", base + 40)]
            if len(reserved_secs) >= 3:
                tiers.append(("P3", base + 10))
            mapping = {}
            for i, s in enumerate(reserved_secs):
                mapping[s] = tiers[min(i, len(tiers) - 1)][0]
            for s in ga_secs:
                mapping[s] = tiers[-1][0]

        # Price-change demo: upcoming_price_change — leave unsold (handled later)
        # upcoming_price_locked — will sell into P1

        for tname, price in tiers:
            tier_rows.append([p_id, tname, f"{price:.2f}"])
        for sec, tname in mapping.items():
            assign_rows.append([vid, sec, p_id, tname])

        meta["tiers"] = {t[0]: t[1] for t in tiers}
        meta["section_tier"] = mapping
        meta["size"] = size

    write_csv("PriceTier.csv", ["performance_id", "tier_name", "price"], tier_rows)
    write_csv(
        "AssignedTo.csv",
        ["venue_id", "section_name", "performance_id", "tier_name"],
        assign_rows,
    )

    # ----- Blocks (some seats blocked on some performances) -----
    block_rows = []
    blocked_set: set[tuple] = set()  # (perf, vid, sec, row, seat)

    def block_seat(p_id: int, vid: int, sec: str, row: str, seat: int, reason: str) -> None:
        key = (p_id, vid, sec, row, seat)
        if key in blocked_set:
            return
        blocked_set.add(key)
        block_rows.append([
            p_id, vid, sec, row, seat,
            dt(perf_meta[p_id]["when"] - timedelta(days=14), 9),
            reason,
        ])

    # Block a few seats on upcoming_demo and some past perfs
    for p_id in [upcoming_demo, upcoming_near, upcoming_price_locked]:
        vid = perf_meta[p_id]["venue_id"]
        seats = all_seats[vid]
        for sec, row, sn in seats[-6:]:  # last few seats in layout
            block_seat(p_id, vid, sec, row, sn, "production equipment")

    for p_id, tag in list(past_tags.items())[:5]:
        vid = perf_meta[p_id]["venue_id"]
        seats = all_seats[vid]
        for sec, row, sn in seats[0:3]:
            block_seat(p_id, vid, sec, row, sn, "obstructed view")

    write_csv(
        "Blocks.csv",
        ["performance_id", "venue_id", "section_name", "row_name", "seat_number",
         "blocked_at", "reason"],
        block_rows,
    )

    # ----- Orders / Tickets -----
    order_rows = []
    ticket_rows = []
    seatticket_rows = []
    gaticket_rows = []

    order_id = 0
    ticket_id = 0

    # Track sold seats / GA counts per performance
    sold_seats: dict[int, set[tuple]] = defaultdict(set)  # perf -> {(sec,row,seat)}
    sold_ga: dict[tuple[int, str], int] = defaultdict(int)  # (perf, section) -> count
    # customer purchase counts for scalpers
    customer_ticket_count: dict[int, int] = defaultdict(int)
    customer_orders: dict[int, list[int]] = defaultdict(list)  # customer -> order ids
    ticket_owner: dict[int, int] = {}  # ticket_id -> current owner customer_id
    ticket_face: dict[int, float] = {}
    ticket_perf: dict[int, int] = {}
    ticket_cancelled: set[int] = set()
    # tickets eligible for resale (uncancelled, past purchase, upcoming or past)
    resale_candidates: list[int] = []

    customers = list(range(6, 116))

    def tier_price_for_section(p_id: int, section: str) -> float:
        tname = perf_meta[p_id]["section_tier"][section]
        return float(perf_meta[p_id]["tiers"][tname])

    def available_seats(p_id: int) -> list[tuple[str, str, int]]:
        vid = perf_meta[p_id]["venue_id"]
        out = []
        for sec, row, sn in all_seats[vid]:
            if (sec, row, sn) in sold_seats[p_id]:
                continue
            if (p_id, vid, sec, row, sn) in blocked_set:
                continue
            out.append((sec, row, sn))
        return out

    def place_order(
        customer_id: int,
        p_id: int,
        order_date: datetime,
        seat_list: list[tuple[str, str, int]] | None = None,
        ga_list: list[tuple[str, int]] | None = None,  # (section, qty)
        cancel: bool = False,
        cancel_reason: str | None = None,
        cancel_at: datetime | None = None,
    ) -> list[int]:
        nonlocal order_id, ticket_id
        if perf_meta[p_id]["cancelled"]:
            return []
        seat_list = seat_list or []
        ga_list = ga_list or []
        if not seat_list and not ga_list:
            return []

        order_id += 1
        order_rows.append([
            order_id,
            order_date.strftime("%Y-%m-%d %H:%M:%S"),
            fake_card(),
            fake_expiry(),
            customer_id,
            p_id,
        ])
        customer_orders[customer_id].append(order_id)
        created_tickets = []

        for sec, row, sn in seat_list:
            if (sec, row, sn) in sold_seats[p_id]:
                continue
            if (p_id, perf_meta[p_id]["venue_id"], sec, row, sn) in blocked_set:
                continue
            price = tier_price_for_section(p_id, sec)
            ticket_id += 1
            cancelled = 1 if cancel else 0
            cancelled_at = cancel_at.strftime("%Y-%m-%d %H:%M:%S") if cancel else ""
            reason = cancel_reason if cancel else ""
            ticket_rows.append([
                ticket_id, f"{price:.2f}", cancelled, cancelled_at, reason, order_id,
            ])
            seatticket_rows.append([
                ticket_id, perf_meta[p_id]["venue_id"], sec, row, sn,
            ])
            if not cancel:
                sold_seats[p_id].add((sec, row, sn))
                ticket_owner[ticket_id] = customer_id
                ticket_face[ticket_id] = price
                ticket_perf[ticket_id] = p_id
                customer_ticket_count[customer_id] += 1
                resale_candidates.append(ticket_id)
            else:
                ticket_cancelled.add(ticket_id)
            created_tickets.append(ticket_id)

        for sec, qty in ga_list:
            vid = perf_meta[p_id]["venue_id"]
            cap = ga_capacity.get((vid, sec), 0)
            for _ in range(qty):
                if sold_ga[(p_id, sec)] >= cap:
                    break
                price = tier_price_for_section(p_id, sec)
                ticket_id += 1
                cancelled = 1 if cancel else 0
                cancelled_at = cancel_at.strftime("%Y-%m-%d %H:%M:%S") if cancel else ""
                reason = cancel_reason if cancel else ""
                ticket_rows.append([
                    ticket_id, f"{price:.2f}", cancelled, cancelled_at, reason, order_id,
                ])
                gaticket_rows.append([ticket_id, vid, sec])
                if not cancel:
                    sold_ga[(p_id, sec)] += 1
                    ticket_owner[ticket_id] = customer_id
                    ticket_face[ticket_id] = price
                    ticket_perf[ticket_id] = p_id
                    customer_ticket_count[customer_id] += 1
                    resale_candidates.append(ticket_id)
                else:
                    ticket_cancelled.add(ticket_id)
                created_tickets.append(ticket_id)

        return created_tickets

    # Helper: sell fraction of capacity
    def sell_performance(p_id: int, target_fraction: float, prefer_customers: list[int] | None = None) -> None:
        if perf_meta[p_id]["cancelled"]:
            return
        avail = available_seats(p_id)
        vid = perf_meta[p_id]["venue_id"]
        # total sellable
        total_reserved = len(all_seats[vid]) - sum(
            1 for sec, row, sn in all_seats[vid]
            if (p_id, vid, sec, row, sn) in blocked_set
        )
        ga_total = sum(
            ga_capacity[(vid, s)] for s in perf_meta[p_id]["section_tier"]
            if (vid, s) in ga_capacity
        )
        sellable = total_reserved + ga_total
        target = int(sellable * target_fraction)

        cust_pool = prefer_customers or customers
        sold = 0
        # Sell reserved first
        RNG.shuffle(avail)
        i = 0
        while sold < target and i < len(avail):
            batch = avail[i:i + RNG.randint(1, 4)]
            i += len(batch)
            if not batch:
                break
            c = RNG.choice(cust_pool)
            when = perf_meta[p_id]["datetime"]
            od = when - timedelta(days=RNG.randint(10, 90), hours=RNG.randint(0, 20))
            place_order(c, p_id, od, seat_list=batch)
            sold += len(batch)

        # GA
        for sec in list(perf_meta[p_id]["section_tier"]):
            if (vid, sec) not in ga_capacity:
                continue
            while sold < target and sold_ga[(p_id, sec)] < ga_capacity[(vid, sec)]:
                qty = min(RNG.randint(1, 3), target - sold, ga_capacity[(vid, sec)] - sold_ga[(p_id, sec)])
                if qty <= 0:
                    break
                c = RNG.choice(cust_pool)
                when = perf_meta[p_id]["datetime"]
                od = when - timedelta(days=RNG.randint(5, 60))
                place_order(c, p_id, od, ga_list=[(sec, qty)])
                sold += qty

    # Sold-out / low / normal past sales
    for p_id, tag in past_tags.items():
        if tag == "soldout":
            sell_performance(p_id, 1.0)
        elif tag == "low":
            sell_performance(p_id, 0.12)
        else:
            sell_performance(p_id, RNG.uniform(0.25, 0.45))

    # Theatre residency past sales
    for p_id, meta in perf_meta.items():
        if meta["event_id"] == 19 and meta["when"] < TODAY and not meta["cancelled"]:
            if p_id not in past_tags:
                sell_performance(p_id, RNG.uniform(0.25, 0.50))

    # Tour past
    for p_id, meta in perf_meta.items():
        if meta["event_id"] == 1 and meta["when"] < TODAY and not meta["cancelled"]:
            if not sold_seats[p_id] and sold_ga.get((p_id, "Standing Pit"), 0) == 0:
                sell_performance(p_id, 0.35)

    # Upcoming demo (>7 days): leave consecutive seats in Floor row A (1-4 free),
    # and make row B only nonconsecutive available (sell 2,4,6 leave 1,3,5...)
    demo_vid = perf_meta[upcoming_demo]["venue_id"]
    # Sell most of Floor except carefully
    floor_seats = [(s, r, n) for s, r, n in all_seats[demo_vid] if s == "Floor"]
    # Row A: leave seats 1-6 available (consecutive), sell rest of Floor later selectively
    row_a = [(s, r, n) for s, r, n in floor_seats if r == "A"]
    row_b = [(s, r, n) for s, r, n in floor_seats if r == "B"]
    # Sell all of Floor C-F and 100/200 levels partially
    to_sell = []
    for s, r, n in available_seats(upcoming_demo):
        if s == "Floor" and r in ("A", "B"):
            continue
        to_sell.append((s, r, n))
    RNG.shuffle(to_sell)
    # Sell ~40% of non A/B
    for i in range(0, int(len(to_sell) * 0.4), 3):
        batch = to_sell[i:i + 3]
        c = RNG.choice(customers)
        place_order(c, upcoming_demo, datetime(2026, 7, 1, 12, 0, 0), seat_list=batch)

    # Row B: sell even seats only → odd seats remain (nonconsecutive)
    even_b = [(s, r, n) for s, r, n in row_b if n % 2 == 0]
    for i in range(0, len(even_b), 2):
        batch = even_b[i:i + 2]
        place_order(RNG.choice(customers), upcoming_demo, datetime(2026, 7, 2, 14, 0, 0), seat_list=batch)
    # Leave some GA capacity on Standing Pit
    place_order(RNG.choice(customers), upcoming_demo, datetime(2026, 7, 3, 10, 0, 0),
                ga_list=[("Standing Pit", 20)])

    # Upcoming near (<7 days) with sold tickets
    sell_performance(upcoming_near, 0.45)

    # Price change locked (has sales in P1)
    sell_performance(upcoming_price_locked, 0.30)
    # upcoming_price_change is left with zero sales in every tier.

    # More upcoming light sales for volume
    for p_id, meta in perf_meta.items():
        if meta["when"] > TODAY and not meta["cancelled"]:
            if p_id in (upcoming_demo, upcoming_price_change):
                continue
            if not sold_seats[p_id] and all(
                sold_ga[(p_id, s)] == 0
                for s in meta["section_tier"]
                if (meta["venue_id"], s) in ga_capacity
            ):
                sell_performance(p_id, RNG.uniform(0.10, 0.35))

    # Ensure we have enough tickets overall — top up past normal perfs
    def total_tickets() -> int:
        return len(ticket_rows)

    # Scalper customers: buy >=10 tickets and list > half
    scalper1, scalper2 = 6, 7
    # Give them many tickets across cities (Toronto venue 1, NY venue 7)
    for p_id, meta in perf_meta.items():
        if meta["when"] < TODAY and not meta["cancelled"] and meta["venue_id"] in (1, 7, 5):
            avail = available_seats(p_id)[:6]
            if len(avail) >= 2:
                place_order(scalper1, p_id, meta["datetime"] - timedelta(days=20), seat_list=avail[:3])
            avail = available_seats(p_id)[:6]
            if len(avail) >= 2:
                place_order(scalper2, p_id, meta["datetime"] - timedelta(days=18), seat_list=avail[:3])

    # Multi-city customers with 2+ orders in past year
    multi_city_cust = [8, 9, 10, 11, 12]
    city_venues = {1: "Toronto", 5: "Vancouver", 7: "New York", 8: "Chicago", 6: "Montreal"}
    for c in multi_city_cust:
        used_cities = set()
        for p_id, meta in perf_meta.items():
            if meta["cancelled"] or meta["when"] > TODAY:
                continue
            city = city_venues.get(meta["venue_id"])
            if city and city not in used_cities:
                avail = available_seats(p_id)[:2]
                if avail:
                    place_order(c, p_id, meta["datetime"] - timedelta(days=15), seat_list=avail)
                    used_cities.add(city)
            if len(used_cities) >= 2 and len(customer_orders[c]) >= 2:
                break

    # Customer cancellations (several customers) — cancel tickets on performances >7 days
    # when they bought them (for past orders on future shows)
    cancel_targets = []
    for tid in list(resale_candidates):
        p_id = ticket_perf[tid]
        if perf_meta[p_id]["when"] >= days_ahead(10):
            cancel_targets.append(tid)
    cancel_targets = cancel_targets[:25]
    # Mark cancellations by updating ticket rows and freeing seats
    ticket_row_by_id = {r[0]: r for r in ticket_rows}
    seat_by_ticket = {r[0]: r for r in seatticket_rows}
    ga_by_ticket = {r[0]: r for r in gaticket_rows}

    cancelled_by_customers = set()
    for tid in cancel_targets:
        if tid not in ticket_owner:
            continue
        row = ticket_row_by_id[tid]
        row[2] = 1
        row[3] = dt(days_ago(2), 11)
        row[4] = "customer_cancelled"
        ticket_cancelled.add(tid)
        cancelled_by_customers.add(ticket_owner[tid])
        if tid in seat_by_ticket:
            st = seat_by_ticket[tid]
            sold_seats[ticket_perf[tid]].discard((st[2], st[3], st[4]))
        if tid in ga_by_ticket:
            gt = ga_by_ticket[tid]
            sold_ga[(ticket_perf[tid], gt[2])] = max(0, sold_ga[(ticket_perf[tid], gt[2])] - 1)
        if tid in resale_candidates:
            resale_candidates.remove(tid)
        del ticket_owner[tid]

    # Sell then cancel for cancelled performances historically
    for p_id, meta in perf_meta.items():
        if not meta["cancelled"]:
            continue
        cancel_ts = performance_rows[p_id - 1][3] or dt(days_ago(35), 12)
        meta["cancelled"] = False
        avail = available_seats(p_id)[:30]
        for i in range(0, len(avail), 3):
            batch = avail[i:i + 3]
            c = RNG.choice(customers)
            od = meta["datetime"] - timedelta(days=40)
            tids = place_order(c, p_id, od, seat_list=batch)
            for tid in tids:
                row = next(r for r in ticket_rows if r[0] == tid)
                row[2] = 1
                row[3] = cancel_ts
                row[4] = "performance_cancelled"
                ticket_cancelled.add(tid)
                st = next(r for r in seatticket_rows if r[0] == tid)
                sold_seats[p_id].discard((st[2], st[3], st[4]))
                if tid in resale_candidates:
                    resale_candidates.remove(tid)
                ticket_owner.pop(tid, None)
        meta["cancelled"] = True

    # Top up until we have >= 800 tickets and >= 300 orders
    guard = 0
    while (len(ticket_rows) < 820 or len(order_rows) < 310) and guard < 5000:
        guard += 1
        p_id = RNG.choice([
            p for p, m in perf_meta.items()
            if not m["cancelled"] and m["when"] < TODAY
        ])
        avail = available_seats(p_id)
        if len(avail) >= 2:
            batch = avail[: RNG.randint(1, min(4, len(avail)))]
            place_order(
                RNG.choice(customers),
                p_id,
                perf_meta[p_id]["datetime"] - timedelta(days=RNG.randint(5, 50)),
                seat_list=batch,
            )
        else:
            vid = perf_meta[p_id]["venue_id"]
            ga_secs = [s for s in perf_meta[p_id]["section_tier"] if (vid, s) in ga_capacity]
            if ga_secs:
                sec = ga_secs[0]
                rem = ga_capacity[(vid, sec)] - sold_ga[(p_id, sec)]
                if rem > 0:
                    place_order(
                        RNG.choice(customers),
                        p_id,
                        perf_meta[p_id]["datetime"] - timedelta(days=10),
                        ga_list=[(sec, min(2, rem))],
                    )

    print(f"  (orders: {len(order_rows)}, tickets: {len(ticket_rows)})")
    print(f"  (customer cancellations by: {len(cancelled_by_customers)} customers)")

    write_csv(
        "Orders.csv",
        ["order_id", "order_date", "card_number", "card_expiry", "user_id", "performance_id"],
        order_rows,
    )
    write_csv(
        "Ticket.csv",
        ["ticket_id", "face_value", "cancelled", "cancelled_at", "cancellation_reason", "order_id"],
        ticket_rows,
    )
    write_csv(
        "SeatTicket.csv",
        ["ticket_id", "venue_id", "section_name", "row_name", "seat_number"],
        seatticket_rows,
    )
    write_csv(
        "GATicket.csv",
        ["ticket_id", "venue_id", "section_name"],
        gaticket_rows,
    )

    # ----- Listings & Resales -----
    listing_rows = []
    resale_rows = []
    listing_id = 0
    resale_id = 0

    # Active, withdrawn, sold listings; some at exact cap
    # Scalpers list > half of their tickets
    def event_cap_pct(p_id: int) -> float:
        eid = perf_meta[p_id]["event_id"]
        return float(next(r[3] for r in event_rows if r[0] == eid))

    def list_ticket(tid: int, seller: int, at_cap: bool = False, status: str = "active",
                    listed_at: datetime | None = None) -> int:
        nonlocal listing_id
        face = ticket_face[tid]
        cap_pct = event_cap_pct(ticket_perf[tid])
        cap_price = round(face * cap_pct / 100, 2)
        if at_cap:
            price = cap_price
        else:
            price = round(face * RNG.uniform(1.0, min(1.15, cap_pct / 100)), 2)
            price = min(price, cap_price)
        listing_id += 1
        la = listed_at or (TODAY - timedelta(days=RNG.randint(1, 60)))
        # status for sold will be set by trigger; we store intended status
        # For load: insert as active/withdrawn; sold ones start active then Resale updates
        load_status = "withdrawn" if status == "withdrawn" else "active"
        listing_rows.append([
            listing_id, f"{price:.2f}", load_status,
            datetime(la.year, la.month, la.day, 12, 0, 0).strftime("%Y-%m-%d %H:%M:%S")
            if isinstance(la, date) and not isinstance(la, datetime)
            else (la.strftime("%Y-%m-%d %H:%M:%S") if isinstance(la, datetime)
                  else dt(la, 12)),
            tid, seller,
        ])
        return listing_id

    # Scalper listings
    scalper_tickets = {
        s: [t for t, o in ticket_owner.items() if o == s]
        for s in (scalper1, scalper2)
    }
    for s, tids in scalper_tickets.items():
        owned = tids[:]
        n_list = max(len(owned) // 2 + 1, 1)
        for tid in owned[:n_list]:
            list_ticket(tid, s, at_cap=(tid % 3 == 0), status="active",
                        listed_at=days_ago(RNG.randint(5, 40)))

    for s in (scalper1, scalper2):
        their_orders = set(customer_orders[s])
        their_tix = [r[0] for r in ticket_rows if r[5] in their_orders]
        print(f"  scalper {s}: purchased {len(their_tix)}, currently owned {len(scalper_tickets[s])}")

    # Withdraw some listings
    withdrawn = 0
    for lid_row in listing_rows:
        if withdrawn >= 8:
            break
        if lid_row[2] == "active" and RNG.random() < 0.3:
            lid_row[2] = "withdrawn"
            withdrawn += 1

    # Completed resales (sold listings)
    sold_listing_ids = []
    active_for_sale = [r for r in listing_rows if r[2] == "active"]
    for r in active_for_sale[:20]:
        lid, price, _, listed_at, tid, seller = r[0], r[1], r[2], r[3], r[4], r[5]
        buyers = [c for c in customers if c != seller]
        buyer = RNG.choice(buyers)
        resale_id += 1
        sold_at = datetime.strptime(listed_at, "%Y-%m-%d %H:%M:%S") + timedelta(days=RNG.randint(1, 10))
        resale_rows.append([
            resale_id,
            sold_at.strftime("%Y-%m-%d %H:%M:%S"),
            lid,
            buyer,
        ])
        sold_listing_ids.append(lid)
        # transfer ownership
        if tid in ticket_owner:
            ticket_owner[tid] = buyer

    # Ticket that changed owners twice
    double_tid = None
    for tid, owner in list(ticket_owner.items()):
        if ticket_perf[tid] and perf_meta[ticket_perf[tid]]["when"] > TODAY:
            prior = [rr for rr in resale_rows if any(
                lr[0] == rr[2] and lr[4] == tid for lr in listing_rows
            )]
            if len(prior) == 1:
                double_tid = tid
                break
    if double_tid is None:
        # create a chain: pick a ticket, list+sell twice
        for tid, owner in list(ticket_owner.items()):
            if tid in ticket_cancelled:
                continue
            double_tid = tid
            break

    if double_tid is not None:
        already = [rr for rr in resale_rows if any(
            lr[0] == rr[2] and lr[4] == double_tid for lr in listing_rows
        )]
        if not already:
            seller = ticket_owner[double_tid]
            lid = list_ticket(double_tid, seller, at_cap=True, listed_at=days_ago(50))
            buyer1 = RNG.choice([c for c in customers if c != seller])
            resale_id += 1
            resale_rows.append([resale_id, dt(days_ago(45), 15), lid, buyer1])
            ticket_owner[double_tid] = buyer1
            already = [resale_rows[-1]]
        # Second resale
        seller2 = ticket_owner[double_tid]
        lid2 = list_ticket(double_tid, seller2, listed_at=days_ago(20))
        buyer2 = RNG.choice([c for c in customers if c != seller2])
        resale_id += 1
        resale_rows.append([resale_id, dt(days_ago(15), 16), lid2, buyer2])
        ticket_owner[double_tid] = buyer2
        print(f"  double-ownership ticket_id={double_tid}")

    # More active listings not sold
    extra = 0
    for tid, owner in list(ticket_owner.items()):
        if extra >= 15:
            break
        if tid in ticket_cancelled:
            continue
        # skip if already has active listing
        if any(lr[4] == tid and lr[2] == "active" for lr in listing_rows):
            # check not sold
            lids = [lr[0] for lr in listing_rows if lr[4] == tid]
            if any(rr[2] in lids for rr in resale_rows):
                continue
            continue
        list_ticket(tid, owner, at_cap=(extra % 4 == 0), listed_at=days_ago(extra + 1))
        extra += 1

    write_csv(
        "Listing.csv",
        ["listing_id", "list_price", "status", "listed_at", "ticket_id", "user_id"],
        listing_rows,
    )
    write_csv(
        "Resale.csv",
        ["resale_id", "sold_at", "listing_id", "user_id"],
        resale_rows,
    )

    # ----- Reviews (10+ events, several each, multi-sentence) -----
    review_rows = []
    review_id = 0
    # Find past non-cancelled performances with attendees
    attendees_by_perf: dict[int, set[int]] = defaultdict(set)
    for tid, owner in ticket_owner.items():
        p_id = ticket_perf.get(tid)
        if p_id and perf_meta[p_id]["when"] < TODAY and not perf_meta[p_id]["cancelled"]:
            attendees_by_perf[p_id].add(owner)
    # Also include customers who bought (even if resold) for past — use order history
    for r in ticket_rows:
        tid, _, cancelled, _, _, oid = r[0], r[1], r[2], r[3], r[4], r[5]
        if cancelled:
            continue
        order = next(o for o in order_rows if o[0] == oid)
        p_id = order[5]
        cust = order[4]
        if perf_meta[p_id]["when"] < TODAY and not perf_meta[p_id]["cancelled"]:
            attendees_by_perf[p_id].add(cust)

    events_reviewed = set()
    used_pair = set()
    for p_id, attendees in sorted(attendees_by_perf.items(), key=lambda x: -len(x[1])):
        if len(events_reviewed) >= 12 and all(
            sum(1 for rr in review_rows if perf_meta[rr[6]]["event_id"] == e) >= 3
            for e in list(events_reviewed)[:10]
        ):
            # enough
            pass
        eid = perf_meta[p_id]["event_id"]
        count_for_event = sum(1 for rr in review_rows if perf_meta[rr[6]]["event_id"] == eid)
        if count_for_event >= 5 and len(events_reviewed) >= 10:
            continue
        for cust in list(attendees)[:4]:
            if (cust, p_id) in used_pair:
                continue
            used_pair.add((cust, p_id))
            tmpl = RNG.choice(REVIEW_TEMPLATES)
            text = tmpl.format(
                adj=RNG.choice(ADJS),
                noun1=RNG.choice(NOUNS),
                noun2=RNG.choice(NOUNS),
                noun3=RNG.choice(NOUNS),
            )
            review_id += 1
            created = perf_meta[p_id]["datetime"] + timedelta(days=RNG.randint(1, 7))
            review_rows.append([
                review_id,
                RNG.randint(3, 5),
                RNG.randint(3, 5),
                text,
                created.strftime("%Y-%m-%d %H:%M:%S"),
                cust,
                p_id,
            ])
            events_reviewed.add(eid)

    write_csv(
        "Review.csv",
        ["review_id", "event_rating", "venue_rating", "comment_text",
         "created_at", "user_id", "performance_id"],
        review_rows,
    )
    print(f"  reviews: {len(review_rows)} across {len(events_reviewed)} events")

    # Final summary
    print("\n=== Summary ===")
    print(f"Venues: {len(venue_rows)}")
    print(f"Events: {len(event_rows)}")
    print(f"Performances: {len(performance_rows)}")
    print(f"Customers: {len(customer_rows)}, Organizers: {len(organizer_rows)}")
    print(f"Orders: {len(order_rows)}, Tickets: {len(ticket_rows)}")
    print(f"Listings: {len(listing_rows)}, Resales: {len(resale_rows)}")
    print(f"Blocks: {len(block_rows)}, Reviews: {len(review_rows)}")
    print("Done.")


if __name__ == "__main__":
    main()
