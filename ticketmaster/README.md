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

## Testing

The test layers follow `../docs/TESTING.md`:

- domain tests are pure Java and have no AWS dependency;
- application tests use fakes for domain gateways;
- infrastructure integration tests use Floci through Testcontainers and exercise the real AWS SDK adapters;
- architecture tests include the bootstrap module on their classpath so the executable composition root is also checked;
- a small real-AWS contract suite can be added separately for behavior that an emulator cannot prove, such as IAM, quotas, networking, MRSC regional behavior, and hard-fencing operations.

Floci integration tests use the `*IT` naming convention and run through Maven Failsafe during `verify`:

```bash
mvn verify
```

Booking integration tests verify DynamoDB transactional seat claiming, checkout/finalization, reconciliation storage, waiting-room state, and seat-map projection. Events verifies canonical Event/Venue reads. Search verifies OpenSearch querying and indexing. Control-plane integration tests verify conditional owner/epoch assignment and transfer semantics.
