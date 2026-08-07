-- =============================================================
-- MyTix : drop.sql
-- Drops the complete schema. Tables are dropped in reverse
-- dependency order so that no foreign key is left dangling.
-- Triggers are dropped with their tables, but are listed
-- explicitly so the script is safe to run after a partial load.
-- =============================================================

USE ticketMaster_db;

DROP TRIGGER IF EXISTS resale_marks_listing_sold;
DROP TRIGGER IF EXISTS assignment_venue_matches;
DROP TRIGGER IF EXISTS listing_within_resale_cap;
DROP TRIGGER IF EXISTS ga_capacity_not_exceeded;
DROP TRIGGER IF EXISTS seat_not_blocked_if_sold;
DROP TRIGGER IF EXISTS seat_not_double_sold;
DROP TRIGGER IF EXISTS user_of_legal_age;

DROP TABLE IF EXISTS Review;
DROP TABLE IF EXISTS Resale;
DROP TABLE IF EXISTS Listing;
DROP TABLE IF EXISTS GATicket;
DROP TABLE IF EXISTS SeatTicket;
DROP TABLE IF EXISTS Ticket;
DROP TABLE IF EXISTS Orders;
DROP TABLE IF EXISTS Blocks;
DROP TABLE IF EXISTS AssignedTo;
DROP TABLE IF EXISTS PriceTier;
DROP TABLE IF EXISTS Performance;
DROP TABLE IF EXISTS Features;
DROP TABLE IF EXISTS Artist;
DROP TABLE IF EXISTS Event;
DROP TABLE IF EXISTS Genre;
DROP TABLE IF EXISTS Seat;
DROP TABLE IF EXISTS SeatRow;
DROP TABLE IF EXISTS GASection;
DROP TABLE IF EXISTS ReservedSection;
DROP TABLE IF EXISTS Section;
DROP TABLE IF EXISTS Venue;
DROP TABLE IF EXISTS PostalCode;
DROP TABLE IF EXISTS Organizer;
DROP TABLE IF EXISTS Customer;
DROP TABLE IF EXISTS User;
DROP DATABASE IF EXISTS ticketMaster_db;

