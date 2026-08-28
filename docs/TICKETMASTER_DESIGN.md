# Ticketmaster System Design

This document captures the Ticketmaster system-design discussion and the implementation decisions made so far. It is intentionally interview-focused: requirements first, followed by the architectural consequences, invariants, and tradeoffs.

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
- Hold creation and checkout are idempotent because clients may retry after losing an HTTP response.
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
        |                              |                    Payment Provider
        |                              |
        +-> Streams -> SQS FIFO -> Search projection
                                       |
                                       v
                              Seat Map Read Model
                                  DynamoDB
```

The system separates highly available read paths from the consistency-sensitive booking path. Read-side data may be eventually consistent; authoritative seat acquisition may not.

## Event Metadata: DynamoDB

Canonical event and venue metadata lives in DynamoDB. The access patterns are simple and key-oriented, and introducing a relational database solely for this metadata does not buy enough to justify another datastore.

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

Customer-facing Event/Venue reads use eventually consistent DynamoDB reads because this path favors availability and is also cacheable at the CDN.

Physical venue data such as sections and seat geometry can also be stored in DynamoDB using access-pattern-oriented keys.

### Events to Search Projection

Search does not read the Events table directly and does not compile against Events Java classes. Events enriches canonical Event metadata with Venue data and emits a denormalized, versioned external projection message.

The implemented production path is:

```text
Events DynamoDB
      |
 DynamoDB Stream
      |
EventSearchProjectionLambdaHandler
      |
  SQS FIFO
      |
SearchProjectionSqsLambdaHandler
      |
EventSearchProjectionConsumer
      |
  OpenSearch
```

The Events projection Lambda uses **strongly consistent** Event/Venue reads even though the customer-facing Events API uses eventual reads. A stream callback should not publish an older canonical representation merely because an eventually consistent read replica lagged the write that triggered the stream record.

The SQS message is a bounded-context integration contract rather than a shared Java type. Version 1 contains `UPSERT` and `DELETE` messages. An UPSERT contains fields such as:

```text
schemaVersion = 1
type = UPSERT
eventId
name
venue
city
startsAtEpochMillis
category
```

SQS FIFO is used for the projection transport:

- `messageGroupId = eventId`, preserving order for changes to one event;
- `messageDeduplicationId = DynamoDB Stream event ID`, suppressing normal producer retries;
- Search indexes documents by `eventId`, so replay is idempotent even beyond the FIFO deduplication window.

Malformed or unsupported messages fail processing rather than being silently dropped. Production queue configuration should include a DLQ/redrive policy for poison messages.

DynamoDB remains the source of truth; OpenSearch is the search projection.

## Search Technology: OpenSearch

OpenSearch handles event discovery because search requires text search, filtering, high read throughput, and low latency while tolerating eventual consistency.

A search document is denormalized around fields such as:

```text
eventId
name
venue
city
startsAt
category
```

The current query model supports text, city, date-range filters, cursor/search-after pagination, and a page-size cap of 100. Stable pagination sorts on event start time plus event ID.

Search results are not authoritative for inventory availability.

### Search Latency and AWS Transport

To support the sub-500 ms target, the Search runtime does not inherit a multi-second backend timeout. The current defaults are approximately:

```text
OpenSearch connect timeout  = 200 ms
OpenSearch response timeout = 450 ms
```

A backend timeout/failure becomes a clean `503` rather than allowing requests to hang far beyond the latency objective. Invalid query input/cursors return `400`.

Local and Floci environments use unsigned HTTP. AWS deployments can enable the OpenSearch Java client's `AwsSdk2Transport` so requests are SigV4 signed. The signing service is `es` for Amazon OpenSearch Service or `aoss` for OpenSearch Serverless.

## CDN Usage

A CDN such as CloudFront sits in front of highly cacheable content and removes read load before requests reach the application tier.

Current HTTP cache intent is explicit:

```text
GET event detail
  public, max-age=60, stale-while-revalidate=300

GET search
  public, max-age=5, stale-while-revalidate=30, stale-if-error=60

GET event section directory
  public, max-age=60, stale-while-revalidate=300

GET live section seats
POST hold
POST checkout
waiting-room state
  no-store
```

Live seat availability is never treated as authoritative CDN data. The seat-map DynamoDB projection is already the intentionally stale availability view, while authoritative inventory decides whether a hold succeeds.

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

An expired `HELD` value may remain stored indefinitely without preventing the seat from being reclaimed. DynamoDB TTL can optionally remove old workflow/history records later for storage housekeeping, but TTL or a cleanup process is not part of ordinary hold correctness.

`CHECKOUT` is intentionally different. Once an external payment may be in flight, reaching `checkoutExpiresAt` does **not** by itself make the seat claimable. Blindly reassigning the seat could race with a late provider success and create a charged customer with no ticket. Checkout expiration is resolved through payment reconciliation: confirm success, or safely cancel/fail the payment before releasing seats.

### Authoritative Price Snapshot

The client selects seat IDs, not a total price. Before creating a hold, Booking strongly reads the requested authoritative seat items and creates a `SeatPriceQuote` from stored `priceAmount` and `priceCurrency` values.

The server sums those prices and stores the total on the Hold. The response returns that server-calculated snapshot.

The quote read and seat claim are separate operations, so the transaction also conditions each claimed seat on its quoted price:

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
  create HoldIdempotency -> Hold mapping
```

If any price changes between quote and claim, the entire hold fails rather than silently selling at a stale price.

### Hold Creation Idempotency

`POST /events/{eventId}/holds` requires an `Idempotency-Key` because a successful transaction may commit even if the client never receives the `201` response.

The key mapping is written **inside the same DynamoDB transaction** as all seat claims and the Hold:

```text
TransactWriteItems
  N x conditional seat claims
  Put Hold
  Put HOLD_IDEMPOTENCY#hash(key) -> holdId
```

On retry:

1. Booking verifies that this deployment is still authorized to write the event.
2. It strongly reads the idempotency mapping.
3. If the prior Hold exists and user/event/seat set matches, it returns that original Hold.
4. If the same key is reused for a materially different request, the API returns `409`.
5. If a mapping exists but references a missing/corrupt Hold, the repository fails closed rather than pretending the request is new.

Concurrent first attempts using the same idempotency key are safe because only one mapping can be conditionally created. A losing transaction can read the winner's mapping and return the same Hold.

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

The Hold groups several exclusive seat claims into one checkout workflow and preserves the authoritative server-side price snapshot used by checkout.

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

For a very popular event, every authoritative seat operation would share the same logical partition-key value.

### Current Authoritative Key

Each event-seat gets its own key:

```text
PK = EVENT#123#SEAT#A10
PK = EVENT#123#SEAT#A11
PK = EVENT#123#SEAT#A12
```

The primary authoritative access pattern is:

> Given an event and a seat, can this seat be atomically acquired?

### Atomic Seat Acquisition

A seat claim uses a conditional write. It succeeds only when the seat is available or its ordinary `HELD` reservation has expired, and when the authoritative price still matches the quoted price.

A `CHECKOUT` seat is not claimable through this path even after its checkout deadline; payment resolution must make release safe first.

If many users compete for the same seat, only one conditional transaction may win. The Floci integration suite includes an actual concurrent same-seat race as well as overlapping multi-seat races to make this NFR executable.

### Multi-Seat Holds

A user commonly selects several seats. Because those items may live under different partition keys, the hold uses `TransactWriteItems` so all seat claims succeed or none do.

Typical purchases contain only a small number of seats, so this remains a bounded transaction rather than a transaction over an entire venue.

## Seat Map Read Model: Second DynamoDB Table

Redis is not required for the seat-map availability path.

The second DynamoDB table is optimized for section reads:

```text
Seat row
--------
PK = EVENT#123#SECTION#101
SK = SEAT#A10
status
price
row
number

Section directory row
---------------------
PK = EVENT#123
SK = SECTION#101
```

The section-directory marker makes `GET /events/{eventId}/sections` a query instead of a table scan.

The two tables have different responsibilities:

```text
AuthoritativeSeatInventory
    source of truth
    optimized for seat-level conditional writes

SeatMapBySection
    eventually consistent projection
    optimized for event-section discovery and section seat queries
```

The UI may display A10 as available after another user has acquired it. That is acceptable: the authoritative hold request fails and the UI refreshes.

### Keeping the Projection Updated

The implemented production path is managed by DynamoDB Streams and Lambda:

```text
Authoritative Booking DynamoDB
          |
     DynamoDB Stream
          |
 Lambda event-source mapping
          |
SeatMapProjectionLambdaHandler
          |
DynamoSeatInventoryStreamProjector
          |
    SeatMapBySection
```

The stream must expose `NEW_IMAGE` or `NEW_AND_OLD_IMAGES`. For each seat image, the read-model repository atomically writes the seat row and its event-level section-directory marker in a DynamoDB transaction.

Projection delay or temporary failure can make the UI stale but cannot cause double booking. Projection writes are idempotent, so Lambda can safely retry a failed batch. The read model can also be rebuilt from authoritative inventory if needed.

## Waiting Room / Admission Control

The waiting room is not required for inventory correctness. Its purpose is to protect the rest of the system during extreme flash crowds and control how many users actively compete for seats.

It can be enabled only for sufficiently hot events. An `EventAdmission` record means admission control is enabled; if the record is absent, the waiting room is disabled and hold creation proceeds without admission gating.

### No Physical Queue Required

Exact FIFO admission is not a requirement, so waiting-room state does not need SQS, Kafka, or a global sequence number.

Each user gets a server-assigned wall-clock timestamp:

```text
WaitingRoomUser
---------------
PK = EVENT#123#USER#456
joinedAt = 2026-08-27T21:00:03.482931Z
```

Each event maintains a watermark:

```text
EventAdmission
--------------
PK = EVENT#123
admittedThrough = 2026-08-27T21:00:02.750000Z
```

Admission is:

```text
user.joinedAt <= event.admittedThrough
```

This is approximately arrival ordered. Small clock-skew reorderings are acceptable.

### Controlling Admission Rate

The admission controller advances the watermark conservatively using downstream health rather than maintaining an exact histogram of queued users.

Useful signals include:

- active admitted shoppers;
- hold-request rate and latency;
- conditional-write failure/error rate;
- DynamoDB throttling/saturation;
- payment-provider latency/error rate;
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

The runtime regulator is safe by default: no configured hot-event IDs means it does no work, and the initial bootstrap health adapter defaults to `OVERLOADED`. Failures are isolated per event so one bad regulation attempt does not block other configured events.

The current hold path strongly reads `EventAdmission` and the user's waiting-room entry before authoritative pricing/inventory. A signed short-lived admission token could later replace these reads as an optimization, but is not required for correctness.

## APIs

### View Event

```http
GET /events/{eventId}
```

Event detail reads are cacheable/eventually consistent and are not authoritative for inventory.

### Search Events

```http
GET /events/search?q=taylor&city=los-angeles&startsAfter=...&startsBefore=...&cursor=...&limit=20
```

Search results can lag DynamoDB and are not authoritative for seat acquisition.

### View Seat Map

```http
GET /events/{eventId}/sections
GET /events/{eventId}/sections/{sectionId}/seats
```

These endpoints use the eventually consistent read model.

### Create Hold

```http
POST /events/{eventId}/holds
Idempotency-Key: <client-generated-key>
```

Example body:

```json
{
  "userId": "user-456",
  "seatIds": ["A10", "A11", "A12"]
}
```

The request never supplies a total price. Booking reads authoritative prices, computes the total, and returns that snapshot in `HoldResponse.totalPrice`.

For a hot event, the request must also be admitted before pricing/inventory is touched. Before those reads, Booking checks that the event's control-plane owner matches the local region. Missing/unreachable/inconsistent ownership fails closed.

The operation is all-or-nothing. Seat conflict, stale quoted price, or idempotency-key misuse returns a conflict rather than a partial hold.

### Checkout Hold

```http
POST /events/{eventId}/holds/{holdId}/checkout
Idempotency-Key: <client-generated-key>
```

Checkout is event-scoped so regional routing/ownership can be validated without a separate global hold lookup. Checkout idempotency prevents duplicate booking/payment workflows.

## Checkout and Payment Workflow

Payment is external and cannot participate in the same ACID transaction as DynamoDB. Checkout is therefore a durable, recoverable workflow.

### Start Checkout

```text
DynamoDB transaction
--------------------
Hold:  ACTIVE -> CHECKOUT_IN_PROGRESS
Seats: HELD -> CHECKOUT, deadline = checkoutExpiresAt
Booking: create PENDING_PAYMENT
```

Once checkout begins, normal short hold expiration cannot release seats underneath an in-flight payment.

### Payment Intent Lifecycle

```text
1. Create Booking(PENDING_PAYMENT)
2. Create Payment Intent at provider
3. Persist paymentIntentId on Booking
4. Confirm / execute Payment Intent
5. Learn final provider status
6. Finalize booking
```

Creating the payment intent should use `bookingId` as a provider idempotency key where supported. If the provider creates the intent and the application crashes before storing its ID, repeating create returns the same provider intent rather than charging twice.

### Finalizing Success or Failure

Success converges in one DynamoDB transaction:

```text
Booking: PENDING_PAYMENT -> CONFIRMED
Hold: CHECKOUT_IN_PROGRESS -> CONVERTED
Seats: CHECKOUT -> BOOKED
```

Safe payment failure/cancellation converges similarly by failing the Booking/Hold and releasing the protected seats.

### Payment Reconciliation

Pending bookings are discoverable through a sparse/sharded GSI:

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

Workers query due items and resolve provider state:

```text
provider = SUCCEEDED
    -> finalize booking

provider = FAILED / CANCELED
    -> fail and release seats

provider = PROCESSING
before checkout deadline
    -> reschedule

provider = PROCESSING
after checkout deadline
    -> request provider cancellation
       |
       +-> canceled/failed -> fail and release
       +-> raced to success -> finalize
       +-> still ambiguous -> keep CHECKOUT protected and retry
```

A local timeout is not enough to safely free inventory once payment may be moving. The provider must reach an outcome that makes release safe.

## Multi-Region Strategy

The read side can be highly available across regions, while authoritative inventory favors consistency over availability.

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

All authoritative operations for one event route to that region. Different events may be distributed across regions, preserving horizontal system-wide scale without concurrent writers for one event's inventory.

### Event Ownership Control Plane

A small control plane answers:

> Which region is currently allowed to own authoritative booking for this event?

Ownership metadata can live in a small DynamoDB MRSC Global Table because it is tiny, low-write, and needs globally coordinated single-item conditional updates:

```text
EventOwnership
--------------
PK = EVENT#123
ownerRegion = us-west-2
epoch = 17
```

The implemented read API is:

```http
GET /control-plane/events/{eventId}/ownership
```

Booking consumes that through its own `EventWriteAuthority` port and HTTP adapter rather than importing ControlPlane classes. Successful local-owner checks may be cached briefly; failures are never cached.

An ownership transfer is a conditional compare-and-swap:

```text
expected WEST / epoch 17
write    EAST / epoch 18
```

This prevents two transfer actors from successfully changing the same ownership version.

### Routing Guard versus Hard Fencing

The owner/epoch check is a routing and defensive guard, **not** the split-brain fence. It is not atomically coupled to a regional DynamoDB seat transaction.

Therefore correctness does not depend on instantaneous ownership-cache invalidation. Controlled failover must first make the old regional writer incapable of mutating authoritative inventory.

### Controlled Failover

```text
WEST fails or becomes unsafe
        |
        v
1. Stop booking for affected events
        |
        v
2. Hard-fence WEST from authoritative writes
        |
        v
3. Ensure EAST replacement inventory is safe/caught up/restored
        |
        v
4. CAS ownership WEST/17 -> EAST/18
        |
        v
5. Enable EAST booking writes
```

The system deliberately accepts temporary booking unavailability during an ambiguous regional failure rather than risk double selling. If near-zero automatic regional failover with zero double booking became a hard requirement, the storage/coordination choice should be reconsidered instead of layering ad-hoc distributed consensus over regional DynamoDB transactions.

The invariant is:

> At most one region may be capable of authoritative writes for an event at a time.

## Current Technology Direction

| Concern | Current Direction | Why |
| --- | --- | --- |
| CDN / static delivery | CloudFront or equivalent | Absorb stable/read-heavy traffic before application servers |
| Event metadata | DynamoDB | Simple key-oriented canonical store |
| Event search | OpenSearch | Full-text/filtering/read throughput; eventual consistency acceptable |
| Events -> Search projection | DynamoDB Stream -> Lambda -> SQS FIFO -> Lambda | Durable bounded-context integration, per-event ordering, replayable/idempotent |
| AWS OpenSearch auth | SigV4 `AwsSdk2Transport` | Deployable against Amazon OpenSearch without leaking AWS concerns into domain/application |
| Authoritative seat inventory | DynamoDB | Conditional writes and distributed event-seat keys |
| Holds / bookings | DynamoDB | Atomic seat/workflow transactions plus query-specific indexes |
| Hold retry safety | Transactional idempotency mapping | Lost responses do not create duplicate/replacement holds |
| Seat-map read model | DynamoDB | Query-efficient section projection without Redis |
| Seat-map updates | DynamoDB Stream -> Lambda | Managed checkpointing/retries, idempotent projection |
| Waiting room | DynamoDB timestamp + watermark | Approximate fairness without physical queue/global sequence |
| Payments | External Payment Intent provider | Durable provider state plus idempotency/reconciliation |
| Multi-region booking | Single writer/home region per event | Consistency over availability, no cross-region double booking |
| Event ownership control plane | DynamoDB MRSC Global Table | Tiny globally coordinated ownership metadata |
| Hard failover fence | Operational/storage isolation before ownership transfer | Ownership metadata alone cannot fence a stale regional writer |

## Key Tradeoffs Established

1. **Availability versus consistency:** event/search/read models may be stale; seat acquisition cannot.
2. **Waiting room versus database correctness:** admission limits load, but DynamoDB conditional transactions prevent double booking.
3. **Approximate fairness versus global sequencing:** timestamp-watermark admission avoids a physical waiting queue because exact arrival ordering is not required.
4. **Hot event versus hot seat:** event+seat keys distribute different seats; contenders for the exact same seat still serialize logically.
5. **Write model versus read model:** authoritative inventory is optimized for exact claims; a separate section model is optimized for browsing.
6. **No Redis by default:** the second DynamoDB table satisfies the seat-map access pattern without another datastore.
7. **Ordinary hold expiration versus cleanup:** expired `HELD` timestamps are reclaimable immediately; no cleanup worker is required for correctness.
8. **Checkout timeout versus payment ambiguity:** protected `CHECKOUT` inventory remains fenced until provider state makes booking or release safe.
9. **Client price versus authoritative price:** display prices may be stale; the hold path strongly re-quotes and binds claims to those prices.
10. **Client retry versus duplicate mutation:** hold and checkout idempotency turn lost responses into safe retries rather than duplicate workflows.
11. **Bounded contexts versus shared models:** Events and Search integrate through versioned JSON over SQS, not shared Java classes or database-schema coupling.
12. **FIFO ordering versus global serialization:** search projection uses per-event FIFO ordering, not one global queue group, so unrelated events remain parallel.
13. **Projection reliability versus bespoke consumers:** Lambda event-source mappings own stream/queue polling, checkpoints, and retries; projection writes remain idempotent.
14. **CDN versus live inventory:** cache stable/read-heavy responses aggressively but never cache live booking state as authority.
15. **Multi-region reads versus writes:** reads may be active-active while each event has one authoritative booking writer.
16. **Ownership cache versus fencing:** a short cache removes control-plane reads from the hot path; correctness comes from hard fencing before transfer, not cache freshness.
17. **Regional failover availability versus correctness:** temporary booking outage is preferable to split-brain inventory; stronger automatic failover requirements may change the datastore choice.
