# Ticketmaster System Design

This document captures the Ticketmaster system-design discussion and the decisions made so far. It is intentionally interview-focused: requirements first, then the architectural consequences and tradeoffs.

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
- Seat prices used for a hold are read from authoritative inventory and calculated by the server; the client never supplies the hold total.
- Ordinary `HELD` seats can be reclaimed lazily after their hold deadline. Seats in `CHECKOUT` are deliberately protected from blind timestamp reclaim because an external payment may still resolve.
- Waiting-room ordering only needs to be approximately fair. We do not require a globally exact real-world arrival order.
- A missing `EventAdmission` record means the waiting room is disabled for that event. Enabling a hot-event waiting room therefore creates its admission record before hold traffic is admitted.

## High-Level Architecture

```text
                              +------------------+
                              | CDN / CloudFront |
                              +--------+---------+
                                       |
                                       v
+---------+                  +---------+----------+
| Client  |----------------->| Ticketmaster APIs |
+---------+                  +---------+----------+
                                       |
              +------------------------+-------------------------+
              |                        |                         |
              v                        v                         v
       Event / Search             Waiting Room             Holds / Booking
              |                        |                         |
        +-----+-----+                  |                         v
        |           |                  |                Authoritative Seat
        v           v                  |                Inventory - DynamoDB
    DynamoDB    OpenSearch             |                         |
   metadata      search                |                         v
                                       |                    Payment Provider
                                       |
                                       v
                              Seat Map Read Model
                                  DynamoDB
```

The system separates highly available read paths from the consistency-sensitive booking path. Read-side data may be eventually consistent; authoritative seat acquisition may not.

## Event Metadata: DynamoDB

Canonical event and venue metadata lives in DynamoDB. The access patterns are simple and key-oriented, and introducing a relational database solely for this metadata does not buy us enough to justify another datastore.

Example:

```text
Event
-----
PK = EVENT#123
name
venueId
startsAt
category
status
description

Venue
-----
PK = VENUE#456
name
city
```

Physical venue data such as sections and seat geometry can also be stored in DynamoDB using access-pattern-oriented keys.

Changes to event metadata asynchronously update OpenSearch. The Search bounded context does not read the Events table directly. Events enriches its Event with canonical Venue metadata and emits a denormalized projection message; Search consumes that message into its own domain and writes OpenSearch.

```text
Event change
     |
     v
Events: load Event + Venue
     |
     v
Denormalized search projection
     |
     v
Search projection consumer
     |
     v
OpenSearch
```

The transport carrying that low-volume metadata projection is intentionally not part of the core requirements yet. The important boundary is that Search does not compile against Events classes or depend on the Events DynamoDB item schema.

DynamoDB is the source of truth; OpenSearch is the search projection.

## Search Technology: OpenSearch

OpenSearch handles event discovery because search requires text search, filtering, high read throughput, and low latency while tolerating eventual consistency.

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

Search results are not authoritative for inventory availability.

## CDN Usage

A CDN such as CloudFront sits in front of highly cacheable content and removes read load before requests reach the application tier.

Good CDN candidates include:

- event images and other static assets;
- venue maps and static seat-map geometry;
- event-detail responses when their cacheability permits it;
- frontend application assets.

Live seat availability is not treated as authoritative CDN data. The seat-map DynamoDB projection already serves as the intentionally stale read model, while the authoritative inventory table decides whether a hold succeeds.

## Seat Hold Model

Selecting seats creates a temporary hold, for example for five minutes.

```text
AVAILABLE
   |
   | create hold
   v
 HELD ------------------------------+
   |                                |
   | begin checkout                 | normal hold expires
   v                                |
CHECKOUT                             |
   |                                |
   | payment succeeds               |
   v                                |
BOOKED                              |
                                    v
                                AVAILABLE

CHECKOUT -- payment safely fails/cancels --> AVAILABLE
```

A seat that is actively held or participating in an unresolved checkout cannot be acquired by another user.

### Expiration Semantics

Correctness does not require an expiration cleanup worker for ordinary pre-checkout holds.

The inventory item contains an expiration timestamp. A seat is logically claimable when either:

- its status is `AVAILABLE`, or
- its status is `HELD` and `holdExpiresAt <= now`.

Conceptually:

```text
if status == AVAILABLE
   OR (status == HELD AND holdExpiresAt <= now)
then
   acquire seat
else
   reject
```

An expired `HELD` value may remain stored indefinitely without preventing the seat from being reclaimed. DynamoDB TTL can optionally remove old hold/workflow records later for storage housekeeping, but TTL or a cleanup process is not part of the ordinary hold correctness mechanism.

`CHECKOUT` is intentionally different. Once an external payment may be in flight, reaching `checkoutExpiresAt` does **not** by itself make the seat claimable. Blindly reassigning the seat could race with a late provider success and create a charged customer with no ticket. Checkout expiration is therefore resolved through payment reconciliation: confirm success, or safely cancel/fail the payment before releasing the seats.

Both the hold and the inventory item may contain expiration/deadline state. This is deliberate denormalization:

- the `Hold` owns the checkout workflow and grouping of seats;
- the inventory item contains enough information to make an atomic availability decision without consulting another record;
- the protected `CHECKOUT` state signals that payment resolution, not timestamp-only reclaim, controls release.

### Authoritative Price Snapshot

The client selects seat IDs, not a total price. Before creating a hold, the booking service strongly reads the requested authoritative seat items and creates a `SeatPriceQuote` from their stored `priceAmount` and `priceCurrency` values.

The server sums those seat prices and stores that total on the hold. The hold response returns the server-calculated total so the customer can see the amount that has been locked for checkout.

The quote read and the seat claim are separate operations, so the transaction also conditions each claimed seat on its quoted price. Conceptually:

```text
quote A10 = $100
quote A11 = $125
        |
        v
server total = $225
        |
        v
TransactWriteItems
  claim A10 only if claimable AND price still $100
  claim A11 only if claimable AND price still $125
  create Hold(totalPrice = $225)
```

If any price changes between the quote and the transactional claim, the entire hold fails rather than silently selling at a stale price.

## Core Data Model

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
status             AVAILABLE | HELD | CHECKOUT | BOOKED
holdId             nullable
holdExpiresAt      nullable
bookingId          nullable
```

The existence of a `Hold` record alone does not determine seat availability. The authoritative event-seat inventory item does.

`CHECKOUT` is a protected reservation state: it still belongs to the hold, but ordinary expired-hold acquisition cannot steal it while payment state is unresolved.

### Hold

```text
Hold
----
holdId
userId
eventId
status              ACTIVE | CHECKOUT_IN_PROGRESS | CONVERTED | FAILED
expiresAt
checkoutExpiresAt   nullable
createdAt
seatIds
totalPrice
```

The hold groups several exclusive seat claims into one checkout workflow and preserves the authoritative server-side price snapshot used by checkout.

### Booking

```text
Booking
-------
bookingId
userId
eventId
holdId
status              PENDING_PAYMENT | CONFIRMED | FAILED
paymentIntentId      nullable
nextReconcileAt      nullable
reconcileShard       nullable
totalAmount
createdAt
```

## Authoritative Seat Inventory: DynamoDB

The authoritative inventory table is designed around exact event-seat acquisition rather than whole-event queries.

### Avoid This Partition Key

```text
PK = EVENT#123
SK = SEAT#A10
```

For a very popular event, every authoritative seat operation would use the same partition-key value, creating an undesirable hot-key pattern.

### Current Authoritative Key

Give each event-seat its own distributed key:

```text
PK = EVENT#123#SEAT#A10
PK = EVENT#123#SEAT#A11
PK = EVENT#123#SEAT#A12
```

Conceptually the key is derived from both `eventId` and `seatId`.

The primary access pattern is:

> Given an event and a seat, can this seat be atomically acquired?

### Atomic Seat Acquisition

A seat claim uses a conditional write. It succeeds only when the seat is currently available or its ordinary `HELD` reservation has expired, and when the authoritative price still matches the price quoted for this hold attempt.

A `CHECKOUT` seat is not claimable through this path even if its checkout deadline has passed; the payment workflow must resolve it first.

If two users compete for the same seat, only one conditional write succeeds. If a seat price changes after the quote but before the claim, the claim fails and the caller must refresh/retry instead of receiving a stale price.

### Multi-Seat Holds

A user commonly selects several seats. Because those items may be distributed across different DynamoDB partition keys, the hold uses a DynamoDB transactional write so that all seat claims succeed or none do.

Typical purchases contain only a small number of seats, so this is a bounded multi-item transaction rather than a transaction over an entire venue.

## Seat Map Read Model: Second DynamoDB Table

Redis is not required for the seat-map availability path.

Use a separate DynamoDB table whose key design is optimized for reading a section of an event.

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

`HELD` and `CHECKOUT` are both unavailable to another shopper in this projection; the distinction exists for the authoritative payment workflow.

This is an intentional consistency tradeoff.

### Keeping the Projection Updated

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

Projection delay or temporary failure is acceptable. It may make the UI stale, but it cannot cause double booking because every hold is checked against authoritative inventory.

The projection can be rebuilt from durable inventory state if necessary.

## Waiting Room / Admission Control

The waiting room is not required for inventory correctness. Its purpose is to protect the rest of the system during extreme flash crowds and to control how many users can actively compete for seats.

It can be enabled only for sufficiently hot events. In the current model, an `EventAdmission` record means admission control is enabled for that event; if the record is absent, the waiting room is disabled and hold creation proceeds without an admission check.

Operationally, a hot event must create its initial admission record before hold traffic is opened. The initial watermark can be set earlier than any legitimate join time so nobody is admitted until the controller intentionally advances it.

### No Physical Queue Required

We do not require exact FIFO ordering, so we do not need SQS, Kafka, or a globally serialized sequence number.

Each waiting user gets a server-assigned wall-clock timestamp and a separately keyed DynamoDB item:

```text
WaitingRoomUser
---------------
PK = EVENT#123#USER#456
joinedAt = 2026-08-27T21:00:03.482931Z
```

The timestamp must be assigned by our server, not the browser. `System.nanoTime()` is not suitable because values from different JVMs cannot be compared globally.

Each event maintains a small admission record:

```text
EventAdmission
--------------
PK = EVENT#123
admittedThrough = 2026-08-27T21:00:02.750000Z
```

Admission is simply:

```text
user.joinedAt <= event.admittedThrough
```

This gives approximately arrival-ordered admission without maintaining a physical queue or sequence generator. Requests arriving on different servers within a very small clock-skew window may be reordered, which is acceptable for this design.

### Controlling Admission Rate

We deliberately do not maintain a histogram or exact count of users between timestamps.

Instead, the admission controller advances `admittedThrough` conservatively and uses downstream health as feedback.

Useful signals include:

- active admitted shoppers;
- hold-request rate and latency;
- conditional-write failure/error rate;
- DynamoDB throttling/saturation;
- payment-provider latency and error rate;
- application/API saturation.

Conceptually:

```text
healthy downstream
    -> advance admittedThrough faster

near capacity
    -> advance slowly or hold steady

overloaded
    -> stop advancing
```

Small, frequent watermark movements reduce the risk of admitting a large burst at once. The tradeoff is that admission rate is approximate rather than exact.

The current hold implementation performs strongly consistent reads of `EventAdmission` and the user's waiting-room entry before touching authoritative seat pricing/inventory. Because the watermark only moves forward, admission is monotonic once granted. A short-lived signed admission token could later replace those per-hold waiting-room reads as a performance optimization, but it is not required for correctness or for the current implementation.

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

Search results can lag DynamoDB. Search is not authoritative for whether a seat can actually be acquired.

### View Seat Map

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
  "userId": "user-456",
  "seatIds": ["A10", "A11", "A12"]
}
```

The request does not contain a total price. The server reads authoritative seat prices, computes the hold total, and returns that price snapshot in `HoldResponse.totalPrice`.

For an event with admission control enabled, the hold is rejected before seat pricing/inventory is touched unless the user's waiting-room entry is at or before the current admission watermark.

The operation is all-or-nothing. If any requested seat cannot be acquired or its price changes after the quote, the hold fails rather than returning a partial group.

### Checkout Hold

```http
POST /holds/{holdId}/checkout
```

Checkout uses an idempotency key so retries do not create duplicate booking/payment workflows.

## Checkout and Payment Workflow

Payment is an external system and cannot participate in the same ACID transaction as DynamoDB. Checkout is therefore a durable, recoverable workflow.

### Start Checkout

When the user begins checkout, atomically transition the hold and its seats into protected checkout and create a pending booking before money can move.

```text
DynamoDB transaction
--------------------
Hold:  ACTIVE -> CHECKOUT_IN_PROGRESS
Seats: HELD -> CHECKOUT, deadline = checkoutExpiresAt
Booking: create PENDING_PAYMENT
```

Once checkout has started, the normal short seat-hold expiration must not expire underneath an in-flight payment. The `CHECKOUT` seat state uses the bounded checkout deadline for workflow timing but is not blindly reclaimable when that deadline arrives.

### Payment Intent Lifecycle

The intended flow is:

```text
1. Create Booking(PENDING_PAYMENT)
2. Create Payment Intent at provider
3. Persist paymentIntentId on Booking
4. Confirm / execute Payment Intent
5. Learn final provider status
6. Finalize booking
```

Creating the intent does not mean the customer has been charged. If checkout is abandoned before confirmation, the unused intent is explicitly resolved by reconciliation at the checkout deadline rather than allowing inventory to race an unresolved payment.

Creating the payment intent should use the `bookingId` as an idempotency key where the provider supports it. This closes the crash window in which the provider creates the intent but our service fails before persisting its ID: retrying the create operation recovers the same intent instead of creating a second one.

### Finalizing a Successful Payment

Once the provider reports success, finalize our internal state idempotently:

```text
DynamoDB transaction
--------------------
Booking: PENDING_PAYMENT -> CONFIRMED
Hold: CHECKOUT_IN_PROGRESS -> CONVERTED
Seats: CHECKOUT -> BOOKED
```

If payment fails or is safely canceled, mark the booking failed and atomically release the `CHECKOUT` seats to `AVAILABLE`.

The same idempotent finalization logic is shared by the synchronous payment path, payment webhooks, and reconciliation workers so races between them are safe.

### Payment Reconciliation

A failure can occur after the provider successfully completes payment but before our database is marked `CONFIRMED`.

Example:

```text
Payment Intent = SUCCEEDED
        |
        X application failure
        |
Booking remains PENDING_PAYMENT
```

Therefore pending bookings must be queryable without scanning the entire Booking table.

Use a sparse/sharded reconciliation GSI:

```text
Booking
-------
status = PENDING_PAYMENT
paymentIntentId = PI789
nextReconcileAt = ...
reconcileShard = hash(bookingId) % N

GSI PaymentReconciliation
-------------------------
PK = reconcileShard
SK = nextReconcileAt#bookingId
```

Reconciliation workers query due items across the bounded set of shards, retrieve the authoritative payment status from the provider, and converge our state:

```text
provider = SUCCEEDED
    -> finalize booking

provider = FAILED / CANCELED
    -> mark failed and release checkout seats

provider = PROCESSING / REQUIRES_PAYMENT_METHOD
AND checkout deadline has not arrived
    -> reschedule reconciliation

provider = PROCESSING / REQUIRES_PAYMENT_METHOD
AND checkout deadline has arrived
    -> request provider cancellation
       |
       +-> cancellation returns CANCELED / FAILED
       |      -> mark booking failed and release seats
       |
       +-> cancellation races with and returns SUCCEEDED
       |      -> finalize booking
       |
       +-> provider remains ambiguous/non-terminal
              -> keep seats in CHECKOUT and retry
```

This distinction is important. Releasing inventory merely because a local timer expired is unsafe once an external provider can still complete payment. The provider must first give us a terminal outcome that makes release safe.

Once a booking reaches a terminal state, remove the sparse-index attributes so it naturally disappears from the reconciliation index.

The normal path remains webhook/synchronous completion; reconciliation is the recovery path for missed or ambiguous outcomes and the safe timeout path for abandoned checkout.

## Multi-Region Strategy

The read side can be highly available across regions, but the authoritative inventory path favors consistency over availability.

### Active-Active Read Paths

Multiple regions may independently serve:

- CDN-backed content;
- event metadata reads;
- OpenSearch queries;
- seat-map projection reads;
- waiting-room checks.

These paths tolerate eventual consistency.

### Single Writer per Event for Booking

Do not allow two regions to independently mutate authoritative inventory for the same event.

Each event has a home booking region:

```text
EVENT#123 -> us-west-2
EVENT#456 -> us-east-1
```

All authoritative operations for a given event route to that event's home region:

- acquire/hold seats;
- release/reclaim seats;
- begin checkout;
- convert seats to booked.

Different events may have different home regions, distributing system-wide load without introducing concurrent writers for the same inventory.

```text
Event A -> West
Event B -> East
Event C -> West
Event D -> Europe
```

A request may arrive at the user's nearest API region and still be forwarded internally to the event's home booking region. The routing decision is explicit metadata rather than an accidental consequence of DNS or load-balancer hashing.

### Event Ownership Control Plane

The control plane is deliberately separate from the high-volume booking data plane. Its state changes rarely and answers one operational question:

> Which region is currently allowed to own authoritative booking for this event?

Use a small DynamoDB Global Table configured for Multi-Region Strong Consistency for this ownership metadata.

```text
EventOwnership
--------------
PK = EVENT#123
ownerRegion = us-west-2
epoch = 17
```

Regional routers may cache this mapping because writes are rare, but cache freshness is never the correctness mechanism. The globally coordinated ownership item is authoritative.

Initial assignment creates epoch 1 only if the event does not already have an owner. A transfer is a single conditional compare-and-swap:

```text
expected: ownerRegion = WEST, epoch = 17
write:    ownerRegion = EAST, epoch = 18
```

Conceptually:

```text
UPDATE EVENT#123
SET ownerRegion = EAST,
    epoch = 18
ONLY IF ownerRegion = WEST
   AND epoch = 17
```

Two operators or automation processes cannot both successfully transfer the same ownership version. Every successful transfer increments the epoch, so stale routing decisions, retries, and restarted processes can be recognized as old.

The ownership table intentionally performs only single-item conditional writes. It does not need DynamoDB transaction APIs. This matters because the authoritative seat inventory still requires multi-item transactions for multi-seat holds and therefore remains a regional transactional table rather than being moved into the MRSC control table.

### Controlled Failover and Hard Fencing

The ownership record says who **should** be the writer. It does not by itself make an isolated old regional inventory incapable of accepting writes. A stale process holding `WEST / epoch 17` could otherwise continue mutating its regional table during a partition.

Therefore automatic failover must remain fail-closed until the old writer is actually fenced.

```text
WEST fails or becomes unsafe
        |
        v
1. Stop new booking for affected events
        |
        v
2. Ensure WEST can no longer mutate authoritative inventory
        |
        v
3. Ensure replacement inventory in EAST is caught up / restored
        |
        v
4. CAS ownership WEST/17 -> EAST/18
        |
        v
5. Enable EAST booking writes
```

The exact hard-fence mechanism is operational rather than a property of the ownership item. Examples include removing the old booking deployment from service and revoking/isolating its storage write path before the replacement writer is enabled.

The design intentionally accepts a temporary booking outage during regional failover rather than risk split-brain inventory and double selling.

The key invariant remains:

> At most one region may be capable of authoritative writes for an event at a time.

## Current Technology Direction

| Concern | Current Direction | Why |
| --- | --- | --- |
| CDN / static delivery | CloudFront or equivalent CDN | Absorb read traffic for static/cacheable content |
| Event metadata source of truth | DynamoDB | Simple key-oriented access patterns; already part of the architecture |
| Event search | OpenSearch | Full-text search, filtering, read throughput; eventual consistency acceptable |
| Authoritative seat inventory | DynamoDB | Conditional writes and distributed event-seat keys |
| Holds / bookings | DynamoDB | Fits transactional workflow with authoritative inventory and query-specific indexes |
| Seat-map read model | DynamoDB | Query-efficient section projection without adding Redis |
| Projection updates | DynamoDB Streams / asynchronous projection messages | Native change feeds where the source item already contains the required data; explicit enrichment at bounded-context boundaries |
| Waiting room | DynamoDB timestamp + admission watermark | No physical queue or global sequence needed; approximate fairness is acceptable |
| Payments | External provider with Payment Intent | Durable provider-side state, idempotency, cancellation at checkout timeout, and reconciliation |
| Multi-region booking | Single writer/home region per event | Prevent cross-region double booking; consistency over availability |
| Event ownership control plane | DynamoDB MRSC Global Table | Tiny low-write state with globally coordinated ownership and conditional epoch changes |

## Key Tradeoffs Established

1. **Availability versus consistency:** event/search/read models can be stale; seat acquisition cannot.
2. **Waiting room versus database correctness:** admission control limits load, but DynamoDB conditional writes prevent double booking.
3. **Approximate fairness versus global sequencing:** timestamp-watermark admission avoids SQS/Kafka/global sequencing because exact request ordering is not a requirement.
4. **Hot event versus hot seat:** distributing inventory by event+seat avoids concentrating the entire event behind one partition key; competition for the exact same seat still serializes logically.
5. **Write model versus read model:** the authoritative table is optimized for exact seat claims while a separate section-oriented DynamoDB table serves seat-map queries.
6. **No Redis by default:** the second DynamoDB table is sufficient for the seat map unless another requirement later justifies Redis.
7. **Ordinary hold expiration versus cleanup:** an expired `HELD` timestamp makes the seat reclaimable immediately; no cleanup worker is required for pre-checkout hold correctness.
8. **Payment state is distributed:** Payment Intent state at the provider and Booking state in DynamoDB converge through idempotent completion, webhooks, cancellation, and reconciliation rather than distributed ACID.
9. **Multi-region reads versus writes:** reads may be active-active, while each event has one authoritative booking writer region.
10. **Control metadata versus hard fencing:** strongly coordinated owner/epoch metadata prevents conflicting ownership transitions, but strict split-brain prevention still requires the previous regional writer to lose write capability before the new writer is enabled.
11. **CDN versus live inventory:** cache stable/read-heavy content aggressively, but never use CDN state as the authority for seat acquisition.
12. **Client display price versus authoritative hold price:** seat-map prices may be stale; the hold service re-reads authoritative prices, calculates the total server-side, and binds the claim transaction to that quote.
13. **Waiting-room optimization versus enforcement:** admission tokens may reduce reads later, but the current correctness/load-shedding boundary is the strongly consistent admission check performed before seat pricing and claims.
14. **Checkout timeout versus payment ambiguity:** a local deadline is not enough to safely free inventory after payment starts. `CHECKOUT` seats stay fenced until provider success is booked or provider failure/cancellation makes release safe.
