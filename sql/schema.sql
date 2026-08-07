-- =============================================================
-- MyTix : schema.sql
-- Creates the complete database schema on MySQL 8.
--
-- Note: the relations named Order and Row in the report are
-- implemented as Orders and SeatRow, since ORDER and ROW are
-- reserved words in MySQL 8.
-- =============================================================

-- -------------------------------------------------------------
-- Users
-- -------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS ticketMaster_db;
USE ticketMaster_db;

CREATE TABLE IF NOT EXISTS User (
    user_id         INT             NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    address         VARCHAR(200)    NOT NULL,
    email           VARCHAR(254)    NOT NULL,
    date_of_birth   DATE            NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE (email)
    -- The requirement that a user be at least 18 cannot be written as a
    -- CHECK constraint, since MySQL 8 forbids non-deterministic functions
    -- such as CURRENT_DATE inside one. It is enforced by a trigger below.
);

CREATE TABLE IF NOT EXISTS Customer (
    user_id         INT             NOT NULL,
    PRIMARY KEY (user_id),
    FOREIGN KEY (user_id) REFERENCES User (user_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Organizer (
    user_id         INT             NOT NULL,
    PRIMARY KEY (user_id),
    FOREIGN KEY (user_id) REFERENCES User (user_id)
        ON DELETE CASCADE
);

-- -------------------------------------------------------------
-- Venues and their seating layout
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS PostalCode (
    postal_code     VARCHAR(12)     NOT NULL,
    city            VARCHAR(100)    NOT NULL,
    country         VARCHAR(100)    NOT NULL,
    PRIMARY KEY (postal_code)
);

CREATE TABLE IF NOT EXISTS Venue (
    venue_id        INT             NOT NULL AUTO_INCREMENT,
    name            VARCHAR(150)    NOT NULL,
    latitude        DECIMAL(9,6)    NOT NULL,
    longitude       DECIMAL(9,6)    NOT NULL,
    street_address  VARCHAR(200)    NOT NULL,
    postal_code     VARCHAR(12)     NOT NULL,
    PRIMARY KEY (venue_id),
    FOREIGN KEY (postal_code) REFERENCES PostalCode (postal_code),
    CONSTRAINT venue_latitude_range  CHECK (latitude  BETWEEN  -90 AND  90),
    CONSTRAINT venue_longitude_range CHECK (longitude BETWEEN -180 AND 180)
);

-- Q3 searches for a venue by its exact address.
CREATE INDEX idx_venue_address ON Venue (street_address, postal_code);

-- Q1 ranks venues by distance from a search point.
CREATE INDEX idx_venue_coords ON Venue (latitude, longitude);

CREATE TABLE IF NOT EXISTS Section (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    PRIMARY KEY (venue_id, section_name),
    FOREIGN KEY (venue_id) REFERENCES Venue (venue_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ReservedSection (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    PRIMARY KEY (venue_id, section_name),
    FOREIGN KEY (venue_id, section_name)
        REFERENCES Section (venue_id, section_name)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS GASection (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    capacity        INT             NOT NULL,
    PRIMARY KEY (venue_id, section_name),
    FOREIGN KEY (venue_id, section_name)
        REFERENCES Section (venue_id, section_name)
        ON DELETE CASCADE,
    CONSTRAINT ga_capacity_positive CHECK (capacity > 0)
);

CREATE TABLE IF NOT EXISTS SeatRow (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    row_name        VARCHAR(20)     NOT NULL,
    PRIMARY KEY (venue_id, section_name, row_name),
    FOREIGN KEY (venue_id, section_name)
        REFERENCES ReservedSection (venue_id, section_name)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Seat (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    row_name        VARCHAR(20)     NOT NULL,
    seat_number     INT             NOT NULL,
    PRIMARY KEY (venue_id, section_name, row_name, seat_number),
    FOREIGN KEY (venue_id, section_name, row_name)
        REFERENCES SeatRow (venue_id, section_name, row_name)
        ON DELETE CASCADE,
    CONSTRAINT seat_number_positive CHECK (seat_number > 0)
);

-- -------------------------------------------------------------
-- Events, artists and performances
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS Genre (
    genre           VARCHAR(60)     NOT NULL,
    segment         VARCHAR(60)     NOT NULL,
    PRIMARY KEY (genre)
);

CREATE TABLE IF NOT EXISTS Event (
    event_id        INT             NOT NULL AUTO_INCREMENT,
    name            VARCHAR(200)    NOT NULL,
    genre           VARCHAR(60)     NOT NULL,
    resale_cap_pct  DECIMAL(5,2)    NOT NULL DEFAULT 120.00,
    user_id         INT             NOT NULL,
    PRIMARY KEY (event_id),
    FOREIGN KEY (genre)   REFERENCES Genre (genre),
    FOREIGN KEY (user_id) REFERENCES Organizer (user_id),
    CONSTRAINT resale_cap_at_least_face_value
        CHECK (resale_cap_pct >= 100.00)
);

CREATE TABLE IF NOT EXISTS Artist (
    artist_id       INT             NOT NULL AUTO_INCREMENT,
    name            VARCHAR(150)    NOT NULL,
    PRIMARY KEY (artist_id)
);

CREATE TABLE IF NOT EXISTS Features (
    event_id        INT             NOT NULL,
    artist_id       INT             NOT NULL,
    billing_order   ENUM('headliner','special guest','opening act') NOT NULL,
    PRIMARY KEY (event_id, artist_id),
    FOREIGN KEY (event_id)  REFERENCES Event (event_id)   ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES Artist (artist_id)
);

CREATE TABLE IF NOT EXISTS Performance (
    performance_id  INT             NOT NULL AUTO_INCREMENT,
    p_datetime      DATETIME        NOT NULL,
    cancelled       BOOLEAN         NOT NULL DEFAULT FALSE,
    cancelled_at    DATETIME                 DEFAULT NULL,
    event_id        INT             NOT NULL,
    venue_id        INT             NOT NULL,
    PRIMARY KEY (performance_id),
    FOREIGN KEY (event_id) REFERENCES Event (event_id) ON DELETE CASCADE,
    FOREIGN KEY (venue_id) REFERENCES Venue (venue_id),
    CONSTRAINT performance_cancellation_consistent
        CHECK ((cancelled = FALSE AND cancelled_at IS NULL)
            OR (cancelled = TRUE  AND cancelled_at IS NOT NULL))
);

-- Q1, Q2, Q4 and Q5 all filter performances by date.
CREATE INDEX idx_performance_datetime ON Performance (p_datetime);

CREATE TABLE IF NOT EXISTS PriceTier (
    performance_id  INT             NOT NULL,
    tier_name       VARCHAR(20)     NOT NULL,
    price           DECIMAL(10,2)   NOT NULL,
    PRIMARY KEY (performance_id, tier_name),
    FOREIGN KEY (performance_id) REFERENCES Performance (performance_id)
        ON DELETE CASCADE,
    CONSTRAINT tier_price_positive CHECK (price > 0)
);

CREATE TABLE IF NOT EXISTS AssignedTo (
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    performance_id  INT             NOT NULL,
    tier_name       VARCHAR(20)     NOT NULL,
    PRIMARY KEY (venue_id, section_name, performance_id),
    FOREIGN KEY (venue_id, section_name)
        REFERENCES Section (venue_id, section_name)
        ON DELETE CASCADE,
    FOREIGN KEY (performance_id, tier_name)
        REFERENCES PriceTier (performance_id, tier_name)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Blocks (
    performance_id  INT             NOT NULL,
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    row_name        VARCHAR(20)     NOT NULL,
    seat_number     INT             NOT NULL,
    blocked_at      DATETIME        NOT NULL,
    reason          VARCHAR(200)             DEFAULT NULL,
    PRIMARY KEY (performance_id, venue_id, section_name, row_name, seat_number),
    FOREIGN KEY (performance_id) REFERENCES Performance (performance_id)
        ON DELETE CASCADE,
    FOREIGN KEY (venue_id, section_name, row_name, seat_number)
        REFERENCES Seat (venue_id, section_name, row_name, seat_number)
        ON DELETE CASCADE
);

-- -------------------------------------------------------------
-- Orders and tickets
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS Orders (
    order_id        INT             NOT NULL AUTO_INCREMENT,
    order_date      DATETIME        NOT NULL,
    card_number     VARCHAR(19)     NOT NULL,
    card_expiry     CHAR(5)         NOT NULL,
    user_id         INT             NOT NULL,
    performance_id  INT             NOT NULL,
    PRIMARY KEY (order_id),
    FOREIGN KEY (user_id)        REFERENCES Customer (user_id),
    FOREIGN KEY (performance_id) REFERENCES Performance (performance_id)
);

-- R1, R5 and R6 aggregate orders over date ranges and customers.
CREATE INDEX idx_orders_date     ON Orders (order_date);
CREATE INDEX idx_orders_customer ON Orders (user_id);

CREATE TABLE IF NOT EXISTS Ticket (
    ticket_id           INT             NOT NULL AUTO_INCREMENT,
    face_value          DECIMAL(10,2)   NOT NULL,
    cancelled           BOOLEAN         NOT NULL DEFAULT FALSE,
    cancelled_at        DATETIME                 DEFAULT NULL,
    cancellation_reason ENUM('customer_cancelled','performance_cancelled')
                                                 DEFAULT NULL,
    order_id            INT             NOT NULL,
    PRIMARY KEY (ticket_id),
    FOREIGN KEY (order_id) REFERENCES Orders (order_id),
    CONSTRAINT ticket_face_value_positive CHECK (face_value > 0),
    CONSTRAINT ticket_cancellation_consistent
        CHECK ((cancelled = FALSE
                AND cancelled_at IS NULL
                AND cancellation_reason IS NULL)
            OR (cancelled = TRUE
                AND cancelled_at IS NOT NULL
                AND cancellation_reason IS NOT NULL))
);

CREATE INDEX idx_ticket_order ON Ticket (order_id);

CREATE TABLE IF NOT EXISTS SeatTicket (
    ticket_id       INT             NOT NULL,
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    row_name        VARCHAR(20)     NOT NULL,
    seat_number     INT             NOT NULL,
    PRIMARY KEY (ticket_id),
    FOREIGN KEY (ticket_id) REFERENCES Ticket (ticket_id)
        ON DELETE CASCADE,
    FOREIGN KEY (venue_id, section_name, row_name, seat_number)
        REFERENCES Seat (venue_id, section_name, row_name, seat_number)
);

-- Supports the double-selling check and the Q6 seat map.
CREATE INDEX idx_seatticket_seat
    ON SeatTicket (venue_id, section_name, row_name, seat_number);

CREATE TABLE IF NOT EXISTS GATicket (
    ticket_id       INT             NOT NULL,
    venue_id        INT             NOT NULL,
    section_name    VARCHAR(60)     NOT NULL,
    PRIMARY KEY (ticket_id),
    FOREIGN KEY (ticket_id) REFERENCES Ticket (ticket_id)
        ON DELETE CASCADE,
    FOREIGN KEY (venue_id, section_name)
        REFERENCES GASection (venue_id, section_name)
);

CREATE INDEX idx_gaticket_section ON GATicket (venue_id, section_name);

-- -------------------------------------------------------------
-- Resale market
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS Listing (
    listing_id      INT             NOT NULL AUTO_INCREMENT,
    list_price      DECIMAL(10,2)   NOT NULL,
    status          ENUM('active','withdrawn','sold') NOT NULL DEFAULT 'active',
    listed_at       DATETIME        NOT NULL,
    ticket_id       INT             NOT NULL,
    user_id         INT             NOT NULL,
    PRIMARY KEY (listing_id),
    FOREIGN KEY (ticket_id) REFERENCES Ticket (ticket_id),
    FOREIGN KEY (user_id)   REFERENCES Customer (user_id),
    CONSTRAINT listing_price_positive CHECK (list_price > 0)
);

CREATE INDEX idx_listing_ticket ON Listing (ticket_id);
CREATE INDEX idx_listing_seller ON Listing (user_id);

CREATE TABLE IF NOT EXISTS Resale (
    resale_id       INT             NOT NULL AUTO_INCREMENT,
    sold_at         DATETIME        NOT NULL,
    listing_id      INT             NOT NULL,
    user_id         INT             NOT NULL,
    PRIMARY KEY (resale_id),
    UNIQUE (listing_id),
    FOREIGN KEY (listing_id) REFERENCES Listing (listing_id),
    FOREIGN KEY (user_id)    REFERENCES Customer (user_id)
);

-- R8 reports resale volume within a period.
CREATE INDEX idx_resale_sold_at ON Resale (sold_at);

-- -------------------------------------------------------------
-- Reviews
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS Review (
    review_id       INT             NOT NULL AUTO_INCREMENT,
    event_rating    TINYINT         NOT NULL,
    venue_rating    TINYINT         NOT NULL,
    comment_text    TEXT            NOT NULL,
    created_at      DATETIME        NOT NULL,
    user_id         INT             NOT NULL,
    performance_id  INT             NOT NULL,
    PRIMARY KEY (review_id),
    UNIQUE (user_id, performance_id),
    FOREIGN KEY (user_id)        REFERENCES Customer (user_id)
        ON DELETE CASCADE,
    FOREIGN KEY (performance_id) REFERENCES Performance (performance_id)
        ON DELETE CASCADE,
    CONSTRAINT event_rating_range CHECK (event_rating BETWEEN 1 AND 5),
    CONSTRAINT venue_rating_range CHECK (venue_rating BETWEEN 1 AND 5)
);

-- =============================================================
-- Triggers
--
-- These enforce rules that span more than one relation and so
-- cannot be written as CHECK constraints.
-- =============================================================

DELIMITER $$

-- -------------------------------------------------------------
-- A user must be at least 18 years old to hold an account.
-- -------------------------------------------------------------
CREATE TRIGGER user_of_legal_age
BEFORE INSERT ON User
FOR EACH ROW
BEGIN
    IF NEW.date_of_birth > (CURRENT_DATE - INTERVAL 18 YEAR) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A user must be at least 18 years old';
    END IF;
END$$

-- -------------------------------------------------------------
-- Checks applied when a reserved seat is sold:
--   the seat must belong to the venue of the performance,
--   the seat must not already be sold for that performance
--     (cancelled tickets release the seat), and
--   the seat must not be blocked for that performance.
-- -------------------------------------------------------------
CREATE TRIGGER seat_not_double_sold
BEFORE INSERT ON SeatTicket
FOR EACH ROW
BEGIN
    DECLARE new_performance INT;
    DECLARE performance_venue INT;

    SELECT o.performance_id, p.venue_id
      INTO new_performance, performance_venue
    FROM Ticket t
        JOIN Orders o      ON o.order_id       = t.order_id
        JOIN Performance p ON p.performance_id = o.performance_id
    WHERE t.ticket_id = NEW.ticket_id;

    IF performance_venue <> NEW.venue_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'Seat does not belong to the venue of this performance';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM SeatTicket st
            JOIN Ticket t ON t.ticket_id = st.ticket_id
            JOIN Orders o ON o.order_id  = t.order_id
        WHERE o.performance_id = new_performance
          AND st.venue_id      = NEW.venue_id
          AND st.section_name  = NEW.section_name
          AND st.row_name      = NEW.row_name
          AND st.seat_number   = NEW.seat_number
          AND t.cancelled      = FALSE
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Seat is already sold for this performance';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM Blocks b
        WHERE b.performance_id = new_performance
          AND b.venue_id       = NEW.venue_id
          AND b.section_name   = NEW.section_name
          AND b.row_name       = NEW.row_name
          AND b.seat_number    = NEW.seat_number
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Seat is blocked for this performance';
    END IF;
END$$

-- -------------------------------------------------------------
-- An organizer cannot block a seat that is already sold.
-- -------------------------------------------------------------
CREATE TRIGGER seat_not_blocked_if_sold
BEFORE INSERT ON Blocks
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1
        FROM SeatTicket st
            JOIN Ticket t ON t.ticket_id = st.ticket_id
            JOIN Orders o ON o.order_id  = t.order_id
        WHERE o.performance_id = NEW.performance_id
          AND st.venue_id      = NEW.venue_id
          AND st.section_name  = NEW.section_name
          AND st.row_name      = NEW.row_name
          AND st.seat_number   = NEW.seat_number
          AND t.cancelled      = FALSE
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Cannot block a seat that is sold';
    END IF;
END$$

-- -------------------------------------------------------------
-- Checks applied when a general admission ticket is sold:
--   the section must belong to the venue of the performance, and
--   the section must not already be at capacity for it.
-- -------------------------------------------------------------
CREATE TRIGGER ga_capacity_not_exceeded
BEFORE INSERT ON GATicket
FOR EACH ROW
BEGIN
    DECLARE new_performance INT;
    DECLARE performance_venue INT;
    DECLARE section_capacity INT;
    DECLARE sold_so_far INT;

    SELECT o.performance_id, p.venue_id
      INTO new_performance, performance_venue
    FROM Ticket t
        JOIN Orders o      ON o.order_id       = t.order_id
        JOIN Performance p ON p.performance_id = o.performance_id
    WHERE t.ticket_id = NEW.ticket_id;

    IF performance_venue <> NEW.venue_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'Section does not belong to the venue of this performance';
    END IF;

    SELECT g.capacity INTO section_capacity
    FROM GASection g
    WHERE g.venue_id     = NEW.venue_id
      AND g.section_name = NEW.section_name;

    SELECT COUNT(*) INTO sold_so_far
    FROM GATicket gt
        JOIN Ticket t ON t.ticket_id = gt.ticket_id
        JOIN Orders o ON o.order_id  = t.order_id
    WHERE o.performance_id = new_performance
      AND gt.venue_id      = NEW.venue_id
      AND gt.section_name  = NEW.section_name
      AND t.cancelled      = FALSE;

    IF sold_so_far >= section_capacity THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'General admission section is at capacity';
    END IF;
END$$

-- -------------------------------------------------------------
-- A listing may not be priced above the resale cap of the event,
-- applied to the face value of the ticket being listed.
-- -------------------------------------------------------------
CREATE TRIGGER listing_within_resale_cap
BEFORE INSERT ON Listing
FOR EACH ROW
BEGIN
    DECLARE cap DECIMAL(10,2);

    SELECT t.face_value * e.resale_cap_pct / 100 INTO cap
    FROM Ticket t
        JOIN Orders o      ON o.order_id       = t.order_id
        JOIN Performance p ON p.performance_id = o.performance_id
        JOIN Event e       ON e.event_id       = p.event_id
    WHERE t.ticket_id = NEW.ticket_id;

    IF NEW.list_price > cap THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Listing price exceeds the resale cap for this event';
    END IF;
END$$

-- -------------------------------------------------------------
-- A section may only be assigned a price tier for a performance
-- that takes place at that section's own venue.
-- -------------------------------------------------------------
CREATE TRIGGER assignment_venue_matches
BEFORE INSERT ON AssignedTo
FOR EACH ROW
BEGIN
    DECLARE performance_venue INT;

    SELECT p.venue_id INTO performance_venue
    FROM Performance p
    WHERE p.performance_id = NEW.performance_id;

    IF performance_venue <> NEW.venue_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'Section does not belong to the venue of this performance';
    END IF;
END$$

-- -------------------------------------------------------------
-- Recording a resale marks its listing as sold.
-- -------------------------------------------------------------
CREATE TRIGGER resale_marks_listing_sold
AFTER INSERT ON Resale
FOR EACH ROW
BEGIN
    UPDATE Listing
    SET status = 'sold'
    WHERE listing_id = NEW.listing_id;
END$$

DELIMITER ;
