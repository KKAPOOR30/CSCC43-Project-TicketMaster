-- =============================================================
-- MyTix : load.sql
-- Bulk-loads all sample data from the data/ directory.
--
-- Run from the project root (so that relative paths resolve):
--   mysql --local-infile=1 -u root ticketMaster_db < sql/load.sql
--
-- Requires: schema.sql already applied; LOCAL INFILE enabled.
-- =============================================================

USE ticketMaster_db;

SET GLOBAL local_infile = 1;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- Allow LOCAL INFILE for this session when the server permits it.
SET SESSION unique_checks = 0;

-- -------------------------------------------------------------
-- Helper macro-style pattern used below:
--   CHARACTER SET utf8mb4
--   FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
--   ESCAPED BY '\\'          -- so \N becomes SQL NULL
--   LINES TERMINATED BY '\n'
--   IGNORE 1 ROWS            -- skip CSV header
-- -------------------------------------------------------------

LOAD DATA LOCAL INFILE '../data/Genre.csv'
INTO TABLE Genre
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(genre, segment);

LOAD DATA LOCAL INFILE 'data/PostalCode.csv'
INTO TABLE PostalCode
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(postal_code, city, country);

LOAD DATA LOCAL INFILE 'data/User.csv'
INTO TABLE `User`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(user_id, name, address, email, date_of_birth);

LOAD DATA LOCAL INFILE 'data/Customer.csv'
INTO TABLE Customer
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(user_id);

LOAD DATA LOCAL INFILE 'data/Organizer.csv'
INTO TABLE Organizer
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(user_id);

LOAD DATA LOCAL INFILE 'data/Venue.csv'
INTO TABLE Venue
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, name, latitude, longitude, street_address, postal_code);

LOAD DATA LOCAL INFILE 'data/Section.csv'
INTO TABLE Section
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name);

LOAD DATA LOCAL INFILE 'data/ReservedSection.csv'
INTO TABLE ReservedSection
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name);

LOAD DATA LOCAL INFILE 'data/GASection.csv'
INTO TABLE GASection
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name, capacity);

LOAD DATA LOCAL INFILE 'data/SeatRow.csv'
INTO TABLE SeatRow
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name, row_name);

LOAD DATA LOCAL INFILE 'data/Seat.csv'
INTO TABLE Seat
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name, row_name, seat_number);

LOAD DATA LOCAL INFILE 'data/Artist.csv'
INTO TABLE Artist
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(artist_id, name);

LOAD DATA LOCAL INFILE 'data/Event.csv'
INTO TABLE Event
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(event_id, name, genre, resale_cap_pct, user_id);

LOAD DATA LOCAL INFILE 'data/Features.csv'
INTO TABLE Features
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(event_id, artist_id, billing_order);

LOAD DATA LOCAL INFILE 'data/Performance.csv'
INTO TABLE Performance
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(performance_id, p_datetime, cancelled, cancelled_at, event_id, venue_id);

LOAD DATA LOCAL INFILE 'data/PriceTier.csv'
INTO TABLE PriceTier
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(performance_id, tier_name, price);

LOAD DATA LOCAL INFILE 'data/AssignedTo.csv'
INTO TABLE AssignedTo
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(venue_id, section_name, performance_id, tier_name);

LOAD DATA LOCAL INFILE 'data/Blocks.csv'
INTO TABLE Blocks
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(performance_id, venue_id, section_name, row_name, seat_number, blocked_at, reason);

LOAD DATA LOCAL INFILE 'data/Orders.csv'
INTO TABLE Orders
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(order_id, order_date, card_number, card_expiry, user_id, performance_id);

LOAD DATA LOCAL INFILE 'data/Ticket.csv'
INTO TABLE Ticket
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(ticket_id, face_value, cancelled, cancelled_at, cancellation_reason, order_id);

-- SeatTicket / GATicket fire inventory triggers; load after Ticket + Blocks.
LOAD DATA LOCAL INFILE 'data/SeatTicket.csv'
INTO TABLE SeatTicket
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(ticket_id, venue_id, section_name, row_name, seat_number);

LOAD DATA LOCAL INFILE 'data/GATicket.csv'
INTO TABLE GATicket
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(ticket_id, venue_id, section_name);

LOAD DATA LOCAL INFILE 'data/Listing.csv'
INTO TABLE Listing
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(listing_id, list_price, status, listed_at, ticket_id, user_id);

-- Resale AFTER INSERT trigger marks the corresponding Listing as sold.
LOAD DATA LOCAL INFILE 'data/Resale.csv'
INTO TABLE Resale
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(resale_id, sold_at, listing_id, user_id);

LOAD DATA LOCAL INFILE 'data/Review.csv'
INTO TABLE Review
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '\\'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(review_id, event_rating, venue_rating, comment_text, created_at, user_id, performance_id);

SET SESSION unique_checks = 1;
SET FOREIGN_KEY_CHECKS = 1;

-- Keep AUTO_INCREMENT counters past the loaded primary keys.
ALTER TABLE `User`       AUTO_INCREMENT = 200;
ALTER TABLE Venue        AUTO_INCREMENT = 20;
ALTER TABLE Artist       AUTO_INCREMENT = 50;
ALTER TABLE Event        AUTO_INCREMENT = 50;
ALTER TABLE Performance  AUTO_INCREMENT = 100;
ALTER TABLE Orders       AUTO_INCREMENT = 2000;
ALTER TABLE Ticket       AUTO_INCREMENT = 5000;
ALTER TABLE Listing      AUTO_INCREMENT = 200;
ALTER TABLE Resale       AUTO_INCREMENT = 100;
ALTER TABLE Review       AUTO_INCREMENT = 200;
