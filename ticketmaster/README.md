# Ticketmaster

The implementation is organized as three bounded contexts. Each context follows the DDD/Clean Architecture module rules documented in `../docs/ARCHITECTURE.md`.

```text
ticketmaster/
├── booking/
├── search/
└── events/
```

Each bounded context contains:

```text
api/
domain/
application/
infrastructure/
  common/
  input/
  output/
architecture/
```

Dependencies point inward. Bounded contexts do not directly depend on one another; integration between them will use APIs or events.

## Testing

The test layers follow `../docs/TESTING.md`:

- domain tests are pure Java and have no AWS dependency;
- application tests use fakes for domain gateways;
- infrastructure integration tests use Floci through Testcontainers and exercise the real AWS SDK adapters;
- a small real-AWS contract suite can be added separately for behavior that an emulator cannot prove, such as IAM, quotas, networking, and regional behavior.

Floci integration tests use the `*IT` naming convention and run through Maven Failsafe during `verify`:

```bash
mvn verify
```

The first integration slice is `booking/infrastructure/output`, where `DynamoHoldRepositoryIT` verifies DynamoDB transactional seat claiming, all-or-none rollback, and expired-hold reclamation.
