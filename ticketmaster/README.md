# Ticketmaster

The implementation is organized as four bounded contexts/components. Each follows the DDD/Clean Architecture module rules documented in `../docs/ARCHITECTURE.md`.

```text
ticketmaster/
├── booking/
├── search/
├── events/
└── controlplane/
```

The customer-facing contexts use the layered shape:

```text
api/
domain/
application/
infrastructure/
  common/
  input/
  output/
architecture/
bootstrap/
```

`controlplane` is deliberately smaller because ownership transfer is an internal operational concern rather than a public customer API. It currently contains domain, application, DynamoDB output, architecture, and bootstrap modules; an operator/admin input adapter can be added when the failover control surface is defined.

Dependencies point inward. Bounded contexts do not directly depend on one another; integration between them uses explicit API or projection/event boundaries. The executable `bootstrap` module is the composition root allowed to depend on both input and output adapters.

## Booking Runtime Notes

Authoritative booking mutations call the ControlPlane ownership API through `EventWriteAuthority`. Successful ownership checks may be cached briefly, but the cache is only a hot-path optimization; controlled failover must still hard-fence the previous writer before ownership changes.

If a request reaches the wrong booking region, the API returns HTTP `421 Misdirected Request` with `X-Booking-Region` identifying the current owner. If ownership cannot be established at all, Booking fails closed with HTTP `503`.

Expected DynamoDB conditional contention remains a booking conflict (`409`). Transaction cancellations that contain throttling, transaction-conflict, capacity, or otherwise non-conditional/unknown reasons are treated as temporary storage unavailability instead. They surface as `503 Service Unavailable` with `Retry-After: 1`, so a caller is not incorrectly told that the selected seats were necessarily taken when the correct action may simply be to retry.

Retryable payment-provider transport failures, throttling, timeouts, and provider `5xx` responses also surface as `503 Service Unavailable` with `Retry-After: 1`. Payment reconciliation advances `nextReconcileAt` before propagating a retryable provider failure, so a degraded provider is not hammered continuously by an already-due Booking. Provider request/contract errors remain distinct failures rather than being mislabeled as transient outages. Early provider callbacks do not push an already-future fallback reconciliation farther out, so duplicate callback delivery cannot indefinitely postpone the scheduler safety net.

Hold creation and checkout both require an `Idempotency-Key`. Hold creation persists the idempotency mapping in the same DynamoDB transaction as the seat claims and Hold record. A retry after a lost successful response therefore returns the original Hold instead of attempting to reacquire the seats. Reusing the same key for a materially different hold request is a conflict.

### Trusted user identity

Waiting-room join/status, hold creation, and checkout all use the same `X-User-Id` request header as the current interview-project representation of authenticated identity. The Hold request body contains seat selection only; it cannot choose its owner. Checkout carries the same identity into the application layer and rejects a Hold or idempotent Booking owned by a different user with HTTP `403` before payment-provider access.

`X-User-Id` is an integration-boundary simplification, not a claim that an internet client may self-assert identity safely. In a production deployment the public ingress/authentication layer must authenticate the user, strip any untrusted inbound copy of this header, and inject the trusted user ID (or the controller would read the authenticated principal directly). The domain/application code consumes the resulting `UserId` and does not depend on the authentication technology.

### Payment gateway modes

Booking defaults to the in-memory demo gateway for local/interview use:

```text
ticketmaster.booking.payment.mode=demo
```

A deployment can instead use the external HTTP payment adapter:

```text
ticketmaster.booking.payment.mode=http
ticketmaster.booking.payment.base-url=https://payments.internal.example
ticketmaster.booking.payment.request-timeout=PT2S
```

The HTTP adapter expects the provider-facing service to expose this small payment-intent contract:

```http
POST /payment-intents
Idempotency-Key: <booking-id>
Content-Type: application/json

{
  "eventId": "event-123",
  "bookingId": "booking-123",
  "amount": 125.00,
  "currency": "USD"
}
```

A successful create/status/cancel response is:

```json
{
  "id": "pi-123",
  "status": "REQUIRES_PAYMENT_METHOD"
}
```

Supported status values are `REQUIRES_PAYMENT_METHOD`, `PROCESSING`, `SUCCEEDED`, `FAILED`, and `CANCELED`. Status and cancellation use:

```http
GET  /payment-intents/{paymentIntentId}
POST /payment-intents/{paymentIntentId}/cancel
```

The Booking ID is the provider idempotency key. If the provider creates an intent but the response is lost before Booking persists the returned ID, a retry therefore converges on the same provider intent instead of creating a second charge workflow. The Event ID is routing metadata retained by the provider-facing service so a later completion callback can reach the authoritative Booking region before that region's storage is accessed.

### Verified payment provider callback

The provider callback endpoint is disabled by default. It can be enabled only with the external HTTP payment mode:

```text
ticketmaster.booking.payment.mode=http
ticketmaster.booking.payment.webhook.enabled=true
ticketmaster.booking.payment.webhook.secret=<shared-secret>
ticketmaster.booking.payment.webhook.max-age=PT5M
```

Enabling the webhook with demo payment mode or a blank secret fails service startup. The endpoint is:

```http
POST /internal/payment-provider/events
X-Payment-Timestamp: <epoch-seconds>
X-Payment-Signature: <hex-hmac-sha256>
Content-Type: application/json

{
  "eventId": "event-123",
  "bookingId": "booking-123"
}
```

The signature is HMAC-SHA256 over the exact bytes of:

```text
<timestamp>.<raw-request-body>
```

The timestamp must be inside the configured replay/clock-skew window and the signature is compared in constant time. Missing, malformed, stale, future-out-of-window, or mismatched authentication returns `401` and never reaches reconciliation.

The callback body deliberately contains only `eventId` and `bookingId`. Unknown properties are rejected with `400`; in particular a provider-supplied `status` field is not accepted. After authentication, `VerifiedPaymentStatusChangedConsumer` checks regional write ownership, loads the Booking, and re-reads authoritative provider state through `PaymentGateway` before confirming or releasing inventory. A valid callback returns `204`; wrong-region routing keeps the existing `421` behavior and retryable payment-provider failures keep the existing `503` + `Retry-After: 1` behavior.

Application-layer HMAC verification does not replace network controls. A production deployment should also restrict this internal endpoint to the provider-facing payment service through the normal private-ingress/service-identity controls.

### Runnable demo payment completion

`DemoPaymentGateway` is intentionally not presented as a production provider integration. By default it creates a pending demo Payment Intent and there is **no client-controlled success endpoint** exposed.

For local demonstration only, the bootstrap module can enable a guarded development endpoint:

```text
ticketmaster.booking.demo-payment-endpoint-enabled=true
```

After `POST /events/{eventId}/holds/{holdId}/checkout` returns a `bookingId`, a local demo can call:

```http
POST /demo/bookings/{bookingId}/payment-success
```

That endpoint marks only the in-memory demo provider intent successful and immediately invokes the normal `ReconcileBookingHandler`. It therefore reaches `Booking=CONFIRMED`, `Hold=CONVERTED`, and `Seat=BOOKED` through the same authoritative finalization transaction used by provider completion/reconciliation. It does **not** bypass booking ownership, hold state, or checkout invariants. The endpoint is disabled unless the property above is explicitly set and must not be enabled in production. If the endpoint is enabled while `ticketmaster.booking.payment.mode=http`, Booking fails startup rather than exposing a demo completion path against a real provider configuration.

### Waiting-room runtime

Waiting-room entries, admission watermarks, and regulator leases use a table separate from the regional authoritative booking table:

```text
ticketmaster.booking.waiting-room-table-name=ticketmaster-waiting-room
```

The intended AWS deployment for this table is a globally consistent DynamoDB table because waiting-room operations are single-item strongly consistent reads/writes/conditionals and do not require DynamoDB transaction APIs. This lets a user join/check admission in multiple regions while the authoritative hold region observes the same admission state.

Waiting-room regulation is schedulable but conservative by default. Runtime properties include:

```text
ticketmaster.booking.admission.event-ids=
ticketmaster.booking.admission.capacity=OVERLOADED
ticketmaster.booking.admission.healthy-advance=PT2S
ticketmaster.booking.admission.constrained-advance=PT0.5S
ticketmaster.booking.admission.poll-delay-ms=1000
ticketmaster.booking.admission.regulator-lease-duration=PT5S
```

An empty `event-ids` value means admission control is not enabled for any configured hot event. Joining a waiting room that has not been enabled returns `409`; latent waiting-room entries are not created before an admission watermark exists.

Every event listed in `event-ids` is initialized synchronously while the scheduler bean is constructed. Initial creation is atomic create-if-absent and starts one millisecond behind the service clock so a join stamped in the same millisecond is still queued. Concurrent service starts cannot move an existing watermark forward, and existing watermarks are preserved across restarts. If initialization fails, service startup fails closed rather than bringing up a configured hot event with its waiting room silently disabled.

Multiple Booking replicas do not multiply the admission rate. Each process receives a unique regulator ID and must acquire/renew a short per-event lease stored on the admission item before running the admission policy. The lease is globally coordinated by the waiting-room table. In addition, a regulator must pass `EventWriteAuthority`; only the event's current authoritative booking region is allowed to advance its watermark, so admission decisions are based on the health of the region that will actually execute holds/checkouts. A non-owner region simply skips regulation for that event. Ownership/control-plane/health/storage failures hold the watermark steady.

The initial `ConfiguredAdmissionHealthGateway` defaults to `OVERLOADED`, so initialization alone never advances admission accidentally. It is intentionally a bootstrap/demo adapter and can be replaced by a telemetry-backed `AdmissionHealthGateway` without changing the application policy.

### Seat-map DynamoDB Stream projection

The authoritative Booking table publishes DynamoDB Stream records to a Lambda event-source mapping. Configure the stream with `NEW_IMAGE` or `NEW_AND_OLD_IMAGES`; the projector requires the new seat image.

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
SeatMapBySection DynamoDB
```

Lambda handler:

```text
com.systemdesign.ticketmaster.booking.bootstrap.SeatMapProjectionLambdaHandler::handleRequest
```

Required environment variable:

```text
TICKETMASTER_SEAT_MAP_TABLE_NAME=<seat-map-table>
```

The Lambda event-source mapping owns stream shard checkpoints, retry behavior, and parallelism. The projection writes are idempotent: each projected seat update atomically writes the section seat row and its event-level section-directory marker. Retrying a batch therefore safely converges on the latest processed seat image. A failed batch is intentionally retried as a batch rather than implementing custom stream checkpointing in the application.

## Events to Search Projection

Events and Search remain separate bounded contexts and do not share Java model classes. The production projection path uses a versioned JSON contract over an SQS FIFO queue:

```text
Events DynamoDB
      |
 DynamoDB Stream
      |
EventSearchProjectionLambdaHandler
      |
  SQS FIFO queue
      |
SearchProjectionSqsLambdaHandler
      |
EventSearchProjectionConsumer
      |
  OpenSearch
```

The Events Lambda uses strongly consistent Event/Venue reads when building a projection even though customer-facing Event reads are eventually consistent. That prevents an asynchronous stream callback from publishing an older canonical Event version because of read-replica lag.

Events producer Lambda:

```text
com.systemdesign.ticketmaster.events.bootstrap.EventSearchProjectionLambdaHandler::handleRequest
```

Required producer environment variables:

```text
TICKETMASTER_EVENTS_TABLE_NAME=<events-table>
TICKETMASTER_SEARCH_PROJECTION_QUEUE_URL=<fifo-queue-url>
```

The queue must be FIFO. `eventId` is the SQS message group ID so updates for one event remain ordered. The DynamoDB Stream event ID is the SQS deduplication ID so normal stream retries do not enqueue duplicate messages inside the FIFO deduplication window. Search indexing is also idempotent by `eventId`, so replay remains safe beyond that window.

Search consumer Lambda:

```text
com.systemdesign.ticketmaster.search.bootstrap.SearchProjectionSqsLambdaHandler::handleRequest
```

Required consumer environment variables:

```text
TICKETMASTER_SEARCH_ENDPOINT=<amazon-opensearch-endpoint>
AWS_REGION=<deployment-region>
```

Optional consumer environment variables:

```text
TICKETMASTER_SEARCH_INDEX_NAME=events
TICKETMASTER_SEARCH_SIGNING_SERVICE=es
```

The Search Lambda uses the OpenSearch Java client's AWS SDK v2 transport, so requests to Amazon OpenSearch are SigV4 signed. Use signing service `es` for Amazon OpenSearch Service and `aoss` for OpenSearch Serverless. The JSON envelope currently uses `schemaVersion=1` and message types `UPSERT` and `DELETE`. Search owns its own input DTOs and translates the envelope into its own domain; it never compiles against Events classes.

Malformed/unsupported projection messages fail the Lambda batch rather than being silently dropped. Because projection writes are idempotent, whole-batch retry is the simple default; production queue configuration should include a DLQ/redrive policy for poison messages.

## Search Runtime Notes

The normal Search HTTP service uses the same OpenSearch client boundary as the projection consumer. Local/Floci development keeps unsigned HTTP enabled by default; AWS deployments can enable SigV4 without changing application or infrastructure-output code:

```text
ticketmaster.search.endpoint=http://localhost:9200
ticketmaster.search.connect-timeout-ms=200
ticketmaster.search.response-timeout-ms=450
ticketmaster.search.aws-signing-enabled=false
ticketmaster.search.aws-signing-service=es
ticketmaster.aws.region=us-west-2
```

For Amazon OpenSearch Service set `ticketmaster.search.aws-signing-enabled=true` with signing service `es`. For OpenSearch Serverless use signing service `aoss`. The 200 ms connect and 450 ms socket/response defaults intentionally bound backend waits to support the sub-500 ms search latency target; callers may receive `503` rather than waiting on a long OpenSearch timeout.

## Testing

The test layers follow `../docs/TESTING.md`:

- domain tests are pure Java and have no AWS dependency;
- application tests use fakes for domain gateways;
- infrastructure integration tests use Floci through Testcontainers and exercise the real AWS SDK adapters;
- architecture tests include the bootstrap module on their classpath so the executable composition root is also checked;
- a small real-AWS contract suite can be added separately for behavior that an emulator cannot prove, such as IAM, quotas, networking, MRSC regional behavior, Lambda event-source mapping configuration, SigV4/IAM integration, and hard-fencing operations.

Floci integration tests use the `*IT` naming convention and run through Maven Failsafe during `verify`:

```bash
mvn verify
```

Booking integration tests verify DynamoDB transactional seat claiming, concurrent no-double-booking behavior, hold idempotency, checkout/finalization, reconciliation storage, waiting-room state, atomic admission initialization, regulator leasing, and seat-map projection. Events verifies canonical Event/Venue reads and SQS FIFO projection publishing. Search verifies OpenSearch querying/indexing plus the SQS projection contract. Control-plane integration tests verify conditional owner/epoch assignment and transfer semantics.
