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

Hold creation and checkout both require an `Idempotency-Key`. Hold creation persists the idempotency mapping in the same DynamoDB transaction as the seat claims and Hold record. A retry after a lost successful response therefore returns the original Hold instead of attempting to reacquire the seats. Reusing the same key for a materially different hold request is a conflict.

Waiting-room regulation is schedulable but conservative by default. Runtime properties include:

```text
ticketmaster.booking.admission.event-ids=
ticketmaster.booking.admission.capacity=OVERLOADED
ticketmaster.booking.admission.healthy-advance=PT2S
ticketmaster.booking.admission.constrained-advance=PT0.5S
ticketmaster.booking.admission.poll-delay-ms=1000
```

An empty `event-ids` value means the admission regulator has no events to process. The initial `ConfiguredAdmissionHealthGateway` defaults to `OVERLOADED`, so simply enabling the service never advances admission accidentally. It is intentionally a bootstrap/demo adapter and can be replaced by a telemetry-backed `AdmissionHealthGateway` without changing the application policy.

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

Booking integration tests verify DynamoDB transactional seat claiming, concurrent no-double-booking behavior, hold idempotency, checkout/finalization, reconciliation storage, waiting-room state, and seat-map projection. Events verifies canonical Event/Venue reads and SQS FIFO projection publishing. Search verifies OpenSearch querying/indexing plus the SQS projection contract. Control-plane integration tests verify conditional owner/epoch assignment and transfer semantics.
