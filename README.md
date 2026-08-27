# System Design

This repository contains system design exercises and implementation prototypes.

## Ticketmaster

The first project is a Java 21 / Spring Boot implementation scaffold for a Ticketmaster-style event discovery and ticket booking system.

Project directory: [`ticketmaster/`](ticketmaster/)

### Functional Requirements

#### Core Requirements

1. Users should be able to view events.
2. Users should be able to search for events.
3. Users should be able to book tickets to events.

#### Below the line (out of scope)

1. Users should be able to view their booked events.
2. Admins or event coordinators should be able to add events.
3. Popular events should have dynamic pricing.

### Non-Functional Requirements

#### Core Requirements

1. The system should prioritize availability for searching and viewing events, but should prioritize consistency for booking events (no double booking).
2. The system should be scalable and able to handle high throughput for popular events (10 million users targeting one event).
3. The system should have low-latency search (< 500 ms).
4. The system is read-heavy and should support high read throughput (approximately 100:1 reads to writes).

## Engineering Guides

The supplied architecture and testing guides are preserved under [`docs/`](docs/):

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/TESTING.md`](docs/TESTING.md)
