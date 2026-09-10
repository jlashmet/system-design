# ChatGPT system design prototype

This directory contains a Java 21 / Spring Boot implementation prototype for the ChatGPT-style system design.

The implementation follows the repository's Clean Architecture / DDD rules and the same module/testing conventions used by the Ticketmaster implementation: the domain is framework-free, application code depends only on the domain, input adapters own HTTP concerns, output adapters implement storage/model-provider gateways, and infrastructure `*IT` tests run through Maven Failsafe against Floci/Testcontainers when AWS behavior is involved.

## Target architecture

```text
Client
  |
  v
API / streaming edge
  |
  v
Conversation application
  |---------------------> Conversation store
  |
  +-> Context assembly / memory / retrieval
  |
  +-> Model router -> inference provider(s)
  |
  +-> Tool execution
  |
  v
Streaming response back to client
```

The conversation service owns durable conversational truth. Model providers, retrieval systems, caches, queues, and tool runtimes are adapters behind explicit ports so they can scale and fail independently without leaking provider details into the domain.

## Implemented slices

### 1. End-to-end conversation seam

- `conversation-domain`: conversation aggregate, ordered messages, repository port, and model gateway port.
- `conversation-application`: create/get/send-message handlers.
- `conversation-api`: transport DTOs with no internal dependencies.
- `conversation-infrastructure-input`: REST endpoints and error mapping.
- `conversation-infrastructure-output`: storage/model-provider adapters.
- `conversation-bootstrap`: Spring Boot composition root.
- `conversation-architecture`: ArchUnit dependency-rule tests.

### 2. Durable, retry-safe turns

`POST /messages` requires an `Idempotency-Key` header. The application creates a durable `Generation` and persists the user message plus pending generation through one atomic `TurnRepository.begin(...)` boundary before inference work can start.

Retry semantics are intentionally explicit: completed replays return the same logical generation, key reuse with different request parameters is rejected, failed provider attempts preserve the user turn, and durable conversational state remains idempotent even though external inference is at-least-once unless a provider offers stronger guarantees.

### 3. Asynchronous inference and token streaming

Message submission no longer invokes the model in the HTTP request lifecycle. `POST /messages` persists the user message and generation, enqueues the generation through an `InferenceJobQueue` port, and returns `202 Accepted` immediately. A separate worker atomically claims generations and publishes model deltas through `GenerationEventBus` to SSE subscribers.

### 4. Generation cancellation

`POST /v1/conversations/{conversationId}/generations/{generationId}/cancel` moves a non-terminal generation to `CANCELLED` and publishes a terminal SSE event. Late provider completion cannot persist an assistant message or overwrite cancellation.

### 5. Queue admission, retries, and dead letters

The development inference queue is bounded (`chatgpt.inference.queue-capacity`, default `1024`). Saturation returns retryable `503 Service Unavailable` while preserving a durable idempotent turn. Provider failures retry up to `chatgpt.inference.max-attempts` (default `3`), then move to the queue's dead-letter sink.

### 6. Health- and cost-aware model routing

Providers advertise supported capabilities, health, and cost through `ModelEndpoint` / `ModelProfile`. The router chooses the lowest-cost healthy eligible endpoint and can fall back before output begins. Once a stream has emitted a token delta, the router will not splice another provider's answer into the same stream.

### 7. Durable routing requirements and per-user quotas

Message requests may include `requiredCapabilities` such as `vision` or `tool_calling`. Those requirements are persisted on `Generation`, included in idempotency equality, and carried through the asynchronous worker into model routing; retries therefore cannot silently change model requirements.

New generations are admitted through an `InferenceQuota` port before durable turn creation. Memory mode uses an idempotency-aware fixed one-minute window. Dynamo mode persists the same fixed-window semantics transactionally with a per-window counter plus per-idempotency admission record, so an accepted replay does not consume quota twice. The default limit is `chatgpt.inference.requests-per-minute=60`. Quota exhaustion returns `429 Too Many Requests` with `Retry-After: 60`.

### 8. Generation-scoped context assembly and token budgeting

The worker no longer sends the whole conversation directly to the model. A `ContextAssembler` owns the inference context, with a `TokenEstimator` port separating model-token accounting from context policy.

`BudgetedContextAssembler` anchors the context at the generation's own durable user message, so a delayed asynchronous worker never sees messages submitted after that generation. It reserves system context first, then fills the remaining input budget with prioritized external context and a contiguous suffix of recent eligible history. The current input budget is configurable with `chatgpt.context.max-input-tokens` (default `8192`). If required system context or the current user message alone cannot fit, context assembly fails explicitly instead of silently truncating required input.

### 9. Prioritized summary, retrieval, and memory sources

`ContextSource` is an explicit port for non-conversation context and identifies each source as `SUMMARY`, `RETRIEVAL`, or `LONG_TERM_MEMORY`, with a declared priority. Spring discovers context-source implementations and injects them into `BudgetedContextAssembler` without changing the worker or model-provider boundary.

Required system context and the generation's current user turn remain non-droppable. The assembler admits source messages in priority order within the remaining token budget before filling unused capacity with recent raw conversation history. If an admitted summary reports that it covers older raw messages, those messages are not redundantly sent to the model.

### 10. Concrete context stores and refresh policy

Context persistence is split by responsibility:

- `ConversationSummaryStore` keeps the latest summary snapshot and its `throughMessageId`.
- `RetrievalContextStore` returns user-scoped snippets ranked for the current query.
- `LongTermMemoryStore` owns user-scoped durable memories.
- `ConversationSummaryRefresher` incrementally advances a summary only after enough unsummarized messages accumulate.

Successful generation completion triggers summary refresh only after the generation has been durably completed and the terminal SSE event has been published. Summary refresh is best-effort: a summary-store or summarizer outage cannot corrupt an already-completed generation.

Memory mode keeps summaries and long-term memories in process. Dynamo mode persists long-term memories in the main composite-key conversation table and summaries in a separate partition-key-only summary table. `DynamoConversationSummaryStore` uses a conditional write so a delayed/stale refresh cannot replace a newer summary.

### 11. Durable DynamoDB conversation and turn storage

`DynamoConversationTurnStore` implements both `ConversationRepository` and `TurnRepository` without storing the entire conversation in one DynamoDB item. Conversation metadata and messages share a conversation partition (`pk`/`sk`), while generation and idempotency records remain directly addressable.

Turn creation uses a DynamoDB transaction to commit the user message, generation, and idempotency mapping atomically. Generation claim/failure/cancellation use conditional state transitions. Completion transactionally writes the assistant message and moves the generation from `RUNNING` to `COMPLETED`; if cancellation wins the race, the transaction cannot leave a late assistant message behind.

Idempotency keys are represented by a SHA-256-derived storage key while the original key is retained in the mapping record. This keeps Dynamo partition keys bounded without weakening the application-level idempotency contract.

Runtime storage is selectable:

```text
chatgpt.storage.mode=memory|dynamo
chatgpt.storage.dynamo.region=us-east-1
chatgpt.storage.dynamo.endpoint=
chatgpt.storage.dynamo.table-name=chatgpt-conversations
chatgpt.storage.dynamo.summary-table-name=chatgpt-conversation-summaries
```

`memory` remains the default. The main Dynamo table uses composite `pk`/`sk` keys. The summary table intentionally uses only partition key `pk`, matching the summary adapter's single-snapshot-per-conversation access pattern. Dynamo table provisioning stays external to application startup.

### 12. Durable SQS inference delivery

`InferenceJobQueue` models an explicit delivery receipt. Workers acknowledge a delivery only after inference succeeds, after a retry has been successfully re-enqueued, or after dead-letter handoff succeeds. A worker crash before acknowledgement therefore leaves the SQS message available for redelivery after its visibility timeout instead of losing the job.

The queue runtime is selectable:

```text
chatgpt.inference.queue-mode=memory|sqs
chatgpt.inference.sqs.region=us-east-1
chatgpt.inference.sqs.endpoint=
chatgpt.inference.sqs.queue-url=<required in sqs mode>
chatgpt.inference.sqs.dead-letter-queue-url=<required in sqs mode>
chatgpt.inference.sqs.visibility-timeout-seconds=60
chatgpt.inference.sqs.wait-time-seconds=10
```

`memory` remains the default. The SQS adapter uses long polling, explicit delete-on-ack, and a separate externally provisioned DLQ.

### 13. Replayable generation event streaming

`ReplayableGenerationEventBus` adds monotonic per-generation sequence IDs on top of the existing generation event publisher. SSE token/lifecycle events carry those IDs, and reconnecting clients may send the standard `Last-Event-ID` header. Only events with a sequence greater than that cursor are replayed before the subscription switches to live delivery.

The in-memory event bus keeps a process-local sequenced history and atomically performs replay-then-subscribe so an event cannot fall into a gap between those phases. For multi-node/restart-safe replay, `DynamoGenerationEventStore` persists sequenced events under a per-generation partition, and `StoredGenerationEventBus` replays from that log then polls for new cross-node events. Sequence allocation uses an atomic per-generation Dynamo counter; sequence gaps are allowed if a writer fails after reserving an ID because the contract requires monotonic ordering rather than contiguity.

Event runtime is selectable:

```text
chatgpt.events.mode=memory|dynamo
chatgpt.events.dynamo.region=us-east-1
chatgpt.events.dynamo.endpoint=
chatgpt.events.dynamo.table-name=chatgpt-generation-events
chatgpt.events.poll-interval-ms=25
chatgpt.events.poll-batch-size=100
```

`memory` remains the default. Dynamo event-table provisioning remains external. Polling is the durable prototype fanout mechanism; a production deployment can replace the live-follow path with Redis Streams/Kinesis/etc. while retaining the same sequence/cursor contract.

## Testing

The testing layout intentionally follows Ticketmaster:

- domain/application tests remain fast and do not require AWS;
- infrastructure integration tests live with the infrastructure module and use `*IT` names;
- AWS integration tests use `io.floci:testcontainers-floci` with JUnit/Testcontainers;
- each integration test creates isolated emulated AWS resources and points AWS SDK clients at the `FlociContainer` endpoint;
- `maven-failsafe-plugin` runs `integration-test` + `verify`, so `mvn verify` exercises the Floci tests in CI.

The Floci suite covers summary stale-write fencing, durable quota replay/exhaustion, SQS acknowledgment/redelivery/dead-letter behavior, generation-event replay, transactional conversation/turn persistence, long-term-memory ordering/upsert behavior, and durable tool-invocation leases/fencing. Retrieval stays in-memory until an actual search/vector backend is introduced rather than pretending a generic Dynamo adapter validates retrieval semantics.

### HTTP endpoints

```text
POST /v1/conversations
GET  /v1/conversations/{conversationId}
POST /v1/conversations/{conversationId}/messages
     Idempotency-Key: <client-generated-key>
     { "content": "...", "requiredCapabilities": ["vision", "tool_calling"] }
GET  /v1/conversations/{conversationId}/generations/{generationId}
GET  /v1/conversations/{conversationId}/generations/{generationId}/events
     Last-Event-ID: <last received event sequence>   # optional on reconnect
POST /v1/conversations/{conversationId}/generations/{generationId}/cancel
```

## Next implementation slices

1. Add paginated/read-optimized conversation message access rather than hydrating unbounded history for client reads.
2. Replace the development retrieval store only when a real search/vector backend is selected.
3. Continue observability and production provider adapters where concrete runtime choices are available.

## Build

```bash
mvn -f chatgpt/pom.xml verify
mvn -f chatgpt/pom.xml -pl conversation/bootstrap -am spring-boot:run
```
