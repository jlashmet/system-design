# Control-plane ownership transfer safety

The Ticketmaster control plane treats event ownership metadata as routing state, not as the split-brain fence for authoritative booking writes.

## Transfer invariant

For one event, a transfer from `(ownerRegion, epoch)` to a new region is allowed only after the old ownership generation is no longer capable of mutating authoritative seat inventory.

The application workflow is deliberately ordered:

```text
1. Read current EventOwnership
2. Verify it matches expectedOwner + expectedEpoch
3. Hard-fence that exact old writer generation
4. CAS ownership expectedOwner/expectedEpoch -> newOwner/(expectedEpoch + 1)
```

A stale transfer command is rejected before any fencing call. If fencing cannot be confirmed, the ownership CAS is never attempted. If fencing succeeds but the ownership CAS subsequently fails or storage becomes unavailable, the safe result is temporary booking unavailability; the old writer is already isolated and the transfer can be retried/reconciled without creating two active writers.

## Domain boundary

`EventWriterFence` is the outbound safety port used by `TransferEventOwnershipHandler`:

```text
assertFenced(eventId, ownerRegion, ownershipEpoch)
```

Returning normally means the implementation has confirmed that this exact ownership generation cannot perform authoritative writes. Failure to prove that condition must throw/fail closed.

The DynamoDB `EventOwnershipRepository.transfer(...)` method remains a conditional metadata primitive. Its CAS is still tested independently, but calling the repository directly is not the production failover workflow.

## Current runtime default

`ControlPlaneApplication` currently wires `FailClosedEventWriterFence`. It never confirms a fence and therefore intentionally makes ownership transfer unavailable at runtime until a deployment-specific isolation adapter is implemented.

This is safer than a demo/no-op fence. An operator or future HTTP endpoint cannot accidentally turn the MRSC ownership row into a false fencing mechanism.

Initial ownership assignment remains separate. `assignIfAbsent` creates the first owner at epoch 1 and does not represent a regional failover.

## Production adapter expectations

A real `EventWriterFence` adapter must integrate with the mechanism that actually removes the old region's write capability. Depending on deployment architecture, that could mean revoking/isolating the old writer's credentials or network/storage access, disabling the regional writer workload, or another storage-enforced isolation mechanism.

The adapter must bind its confirmation to the requested `eventId`, `ownerRegion`, and `ownershipEpoch`. A generic health check, process shutdown request, or ownership-table update is not sufficient proof.

The control-plane mutation surface should remain unexposed until such an adapter exists and its operational workflow is tested. The existing ownership read API is unaffected.
