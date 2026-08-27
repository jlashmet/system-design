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
