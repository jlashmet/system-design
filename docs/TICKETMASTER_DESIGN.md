# Ticketmaster System Design

This document captures the Ticketmaster system-design discussion and decisions made so far. It is intentionally interview-focused: requirements first, then the architectural consequences and tradeoffs.

## Scope

### Core Functional Requirements

1. Users can view events.
2. Users can search for events.
3. Users can select seats and temporarily hold them while completing checkout.
4. Users can book the seats in an active hold.
5. A seat must never be sold to more than one user.

### Below the Line / Out of Scope

1. Users viewing their booked events.
2. Admins or event coordinators adding events.
3. Dynamic pricing for popular events.

## Non-Functional Requirements

1. Favor availability for event viewing and search, while favoring consistency for seat holds and booking.
2. Support extreme demand for a hot event, including a scenario where roughly 10 million users are interested in the same event.
3. Search should be low latency, targeting less than 500 ms.
4. The system is read-heavy, approximately 100:1 reads to writes.
5. The booking path must prevent double booking even under high concurrency.

## Important Interview Assumptions

- We assume assigned seating because it is the harder inventory problem. General admission can be modeled as inventory counts using similar concurrency principles.
- Search and seat-map views may be slightly stale.
- The authoritative hold operation must be strongly consistent.
- A user should hold all requested seats or none of them; partial fulfillment is not assumed.

## Seat Hold Model

Selecting seats creates a temporary hold, for example for five minutes.

```text
AVAILABLE
   |
   | create hold
   v
 HELD ----------------------+
   |                        |
   | checkout succeeds      | hold expires / checkout fails
   v                        |
BOOKED                      |
                            v
                        AVAILABLE
```

A seat that is actively held cannot be held or booked by another user.

### Expiration Semantics

Correctness does not depend on a background cleanup job running exactly on time. The inventory item contains an expiration timestamp.

A seat is logically claimable when either:

- its status is `AVAILABLE`, or
- its status is `HELD` and `holdExpiresAt <= now`.

A background expiration process may later clean up expired holds and return their stored status to `AVAILABLE`, but that process is housekeeping rather than the correctness mechanism.

Both the hold and the inventory item may contain the expiration time. This is deliberate denormalization:

- the `Hold` owns the checkout workflow and grouping of seats;
- the inventory item contains enough information to make an atomic availability decision without consulting another record.

## Waiting Room / Admission Control

A popular event creates a hot-event problem: millions of users may simultaneously target the same inventory pool. We do not want all of those users reaching the consistency-sensitive booking path.

A per-event waiting room provides admission control:

```text
10M interested users
        |
        v
  Waiting Room
        |
        | controlled admission
        v
 Active shoppers
        |
        v
 Seat hold / booking path
```

The waiting room is not the mechanism that prevents double booking. Its job is to protect downstream capacity and reduce contention. The inventory store still provides the authoritative concurrency control.

Important properties:

- admission can be enabled for individual hot events rather than the entire site;
- queue position may be approximate;
- admission itself must be authoritative;
- an admitted user receives a short-lived admission token;
- admission rate can be reduced when downstream latency, saturation, or error rates rise and increased when capacity is healthy.

## High-Level Architecture

```text
                         +----------------+
                         |   Waiting Room |
                         +--------+-------+
                                  |
                                  v
+---------+       +---------------+---------------+
| Client  |------>|       Ticketmaster APIs       |
+---------+       +---------------+---------------+
                                  |
             +--------------------+--------------------+
             |                    |                    |
             v                    v                    v
        Event Reads           Search             Holds/Booking
             |                    |                    |
             v                    v                    v
        Read Models          OpenSearch       Authoritative
                                              Seat Inventory
```

The system separates read models from the authoritative booking model. Read-side data is allowed to be eventually consistent; the hold operation is not.

## Core Data Model

### Event

```text
Event
-----
eventId
name
venueId
startsAt
category
status
```

### Seat

Represents the physical seat in a venue.

```text
Seat
----
seatId
venueId
section
row
number
```

### Event Seat Inventory

Represents a physical seat for one particular event and is the source of truth for exclusivity.

```text
EventSeatInventory
------------------
eventId
seatId
price
status             AVAILABLE | HELD | BOOKED
holdId             nullable
holdExpiresAt      nullable
bookingId          nullable
```

The existence of a `Hold` record alone does not determine seat availability. The authoritative event-seat inventory item does.

### Hold

```text
Hold
----
holdId
userId
eventId
status              ACTIVE | EXPIRED | CONVERTED
expiresAt
createdAt
seatIds
```

The hold groups several exclusive seat claims into one checkout workflow.

### Booking

```text
Booking
-------
bookingId
userId
eventId
holdId
status              PENDING_PAYMENT | CONFIRMED | FAILED
totalAmount
createdAt
```

## APIs Discussed

### View Event

```http
GET /events/{eventId}
```

Event detail reads are highly cacheable/eventually consistent and are not the authoritative source for inventory.

### Search Events

```http
GET /events/search?q=taylor&city=los-angeles&from=...&to=...
```

Search results can lag the transactional data. Search is not authoritative for whether a seat can actually be acquired.

### View Seat Map

Rather than always returning every seat with event details, load availability at a section level.

```http
GET /events/{eventId}/sections
GET /events/{eventId}/sections/{sectionId}/seats
```

These endpoints use the read model and may be slightly stale.

### Create Hold

```http
POST /events/{eventId}/holds
```

Example:

```json
{
  "seatIds": ["A10", "A11", "A12"]
}
```

The operation is all-or-nothing. If any requested seat cannot be acquired, the hold fails rather than returning a partial group.

### Checkout Hold

```http
POST /holds/{holdId}/checkout
```

Checkout should use an idempotency key so retries do not charge the customer twice or create duplicate bookings.

## Search Technology: OpenSearch

OpenSearch is the current choice for event discovery because search requires text search, filtering, high read throughput, and low latency while tolerating eventual consistency.

A search document can be denormalized around fields such as:

```text
eventId
name
performers
venue
city
startsAt
category
```

The search index is a projection, not the system of record.

## Seat Inventory: DynamoDB Direction

We debated PostgreSQL versus DynamoDB for the consistency-sensitive seat inventory.

PostgreSQL provides a simple transactional model for row-level claims and multi-seat transactions, but it does not inherently remove hot-event contention. Many competing requests for the same logical seat still serialize somewhere, regardless of database technology.

The current direction is DynamoDB because we can distribute authoritative event-seat items across partition keys rather than placing every seat for an event behind one `eventId` partition key.

### Avoid This Partition Key

```text
PK = EVENT#123
SK = SEAT#A10
```

For a very popular event, every authoritative seat operation would use the same partition-key value, creating an undesirable hot-key pattern.

### Current Authoritative Key Direction

Give each event-seat a distributed key:

```text
PK = EVENT#123#SEAT#A10
PK = EVENT#123#SEAT#A11
PK = EVENT#123#SEAT#A12
```

Conceptually the key is derived from both `eventId` and `seatId`.

This optimizes the authoritative table around its primary access pattern:

> Given an event and a seat, can this seat be atomically acquired?

The authoritative table does not need to be optimized for returning every seat in an event.

### Atomic Seat Acquisition

A seat claim uses a conditional write. It succeeds only when the seat is currently available or its previous hold has expired.

Conceptually:

```text
if status == AVAILABLE
   OR (status == HELD AND holdExpiresAt <= now)
then
   set status = HELD
   set holdId
   set holdExpiresAt
else
   fail
```

If two users compete for the same seat, only one conditional write succeeds.

### Multi-Seat Holds

A user commonly selects several seats. Because those items may be distributed across different DynamoDB partition keys, the hold must use a DynamoDB transactional write so that all seat claims succeed or none do.

Typical purchases contain only a small number of seats, so this is a bounded multi-item transaction rather than a transaction over an entire venue.

## Seat Map Read Model: Second DynamoDB Table

We do not currently need Redis for the seat map.

Instead, use a separate DynamoDB table whose key design is optimized for reading a section of an event.

Example:

```text
SeatMapBySection
----------------
PK = EVENT#123#SECTION#101
SK = SEAT#A10
status
price
row
number
```

The two DynamoDB tables have different responsibilities:

```text
AuthoritativeSeatInventory
    source of truth
    optimized for seat-level conditional writes

SeatMapBySection
    eventually consistent projection
    optimized for section-level queries
```

The UI reads the seat-map projection. If it displays A10 as available but another user has already acquired A10, the authoritative hold request fails and the UI refreshes.

This is an intentional consistency tradeoff.

## Keeping the Read Projection Updated

A natural DynamoDB-native design is:

```text
AuthoritativeSeatInventory
          |
          | DynamoDB Streams
          v
   Projection Consumer
          |
          v
    SeatMapBySection
```

When a seat transitions from `AVAILABLE` to `HELD`, `HELD` to `BOOKED`, or an expired hold is reclaimed, the stream drives the eventually consistent read projection.

The projection is not authoritative and can be rebuilt from durable state if necessary.

## Expired Hold Cleanup

Although expired seats are logically claimable based on `holdExpiresAt`, a background process should still clean old state.

Its responsibilities include:

- marking expired `Hold` records as `EXPIRED`;
- clearing stale `holdId` / expiration metadata where useful;
- updating the read projection through the normal stream/change path.

Failure or delay of this process must not prevent an expired seat from being acquired.

## Payment / Booking Workflow

Payment cannot participate in the same ACID transaction as the inventory database, so checkout is a recoverable workflow rather than a distributed database transaction.

```text
ACTIVE HOLD
    |
    v
create Booking(PENDING_PAYMENT)
    |
    v
call payment provider
    |
    +----------------+
    |                |
 success           failure
    |                |
    v                v
CONFIRMED          FAILED
    |                |
    v                v
seats BOOKED      release hold
```

Retries must be idempotent, and reconciliation is needed for ambiguous external payment outcomes.

The price should be captured at hold time so checkout uses the price the user selected rather than a subsequently changed value.

## Current Technology Direction

| Concern | Current Direction | Why |
| --- | --- | --- |
| Event search | OpenSearch | Full-text search, filtering, read throughput, eventual consistency is acceptable |
| Authoritative seat inventory | DynamoDB | Conditional writes and distributed event-seat keys |
| Seat-map read model | DynamoDB | Query-efficient section projection without adding Redis |
| Projection updates | DynamoDB Streams | Native change feed from authoritative inventory |
| Waiting room | TBD | Needs high-scale per-event admission control; technology not selected yet |
| Event metadata source of truth | TBD | Not yet settled |
| Holds / bookings storage | TBD | Not yet settled; may use DynamoDB but still needs explicit discussion |
| Payments | External provider | Requires idempotency and recovery rather than distributed ACID |

## Key Tradeoffs Established So Far

1. **Availability versus consistency:** event/search/read models can be stale; seat acquisition cannot.
2. **Waiting room versus database correctness:** admission control limits load, but the inventory conditional write prevents double booking.
3. **Hot event versus hot seat:** distributing seats avoids concentrating an entire event behind one partition key, but many users targeting the exact same seat still contend on one logical item. No datastore removes that fundamental serialization requirement.
4. **Write model versus read model:** do not choose the authoritative key merely to support the seat-map query. Use separate models optimized for their respective access patterns.
5. **No Redis by default:** a second DynamoDB projection table can serve the seat map, avoiding another datastore unless a later requirement justifies it.
6. **Expiration timestamp versus expiration job:** the timestamp determines correctness; the job performs cleanup.

## Open Questions for the Next Design Discussion

- What should be the source of truth for event and venue metadata?
- Should `Hold` and `Booking` also live in DynamoDB, or would another datastore give us useful transactional/query properties?
- What exact DynamoDB item/table design should we use for atomic multi-seat holds and hold metadata?
- What technology and data model should back the waiting room?
- How should event and seat-map projections be rebuilt after failure?
- What availability, durability, and multi-region strategy do we want for the authoritative booking path?
- What are the expected peak hold attempts per second after waiting-room admission control?
