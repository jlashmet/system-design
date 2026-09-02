# Booking write fencing

Booking uses a single authoritative writer region per event. Control-plane ownership is useful for routing and fast rejection, but it is not the final correctness boundary for inventory mutation.

## Authoritative fence row

Each regional authoritative Booking table contains one fence row per writable event:

```text
pk          = EVENT#<eventId>#OWNERSHIP
entityType  = EVENT_OWNERSHIP
eventId     = <eventId>
ownerRegion = <region>
epoch       = <monotonically increasing integer>
```

The control/failover workflow is the only authority allowed to create or advance this row. A Booking data-plane process must never make itself authoritative merely because a control-plane lookup says that it should be.

Before a seat-changing DynamoDB transaction, the regional adapter strongly reads the local fence row to obtain its current epoch. The transaction then includes a `ConditionCheck` for the exact `eventId`, `ownerRegion`, and `epoch` alongside the hold/seat/booking mutations. If ownership changes between the read and the transaction commit, the condition fails and no inventory mutation commits.

The local read is not a remote control-plane poll. It reads the same authoritative DynamoDB consistency domain that commits the inventory transaction, and the condition closes the read/write race atomically.

## Hold

A hold transaction contains:

```text
ConditionCheck EVENT#<eventId>#OWNERSHIP = local region / expected epoch
Update        selected seat(s), conditionally claiming them
Put           Hold
Put           hold idempotency mapping
```

The fence consumes one DynamoDB transaction item. Checkout consumes three non-seat items in addition to the fence (Hold, Booking, checkout idempotency), so the public hold limit is 96 seats to keep checkout within DynamoDB's 100-item transaction maximum.

## Checkout and reconciliation

Every transaction that changes seat state during checkout is fenced the same way:

- start checkout: HELD -> CHECKOUT;
- successful reconciliation: CHECKOUT -> BOOKED;
- failed reconciliation: CHECKOUT -> AVAILABLE.

Payment callbacks and the reconciliation scheduler therefore cannot finalize or release inventory from a stale regional writer.

## Cross-region failover

The epoch condition protects against a stale process while all contenders share the same authoritative regional table. Cross-region promotion between independent transactional tables additionally requires a hard, sequenced handoff:

1. stop routing new writes to the old region;
2. hard-fence the old regional writer so it can no longer commit Booking writes;
3. verify that fence has taken effect and establish the recovery data point;
4. create/advance the new region's event fence to a strictly higher epoch;
5. enable the new regional writer;
6. publish the new ownership for routing/cache convergence.

There may be an interval in which neither region accepts writes. There must never be an interval in which both regions can commit writes for the same event.

A notification or cached ownership value is only an optimization. Missing or delayed notifications cannot grant write authority because the authoritative DynamoDB transaction still checks the local event fence.
