# ChatGPT system design prototype

This directory contains a Java 21 / Spring Boot implementation prototype for the ChatGPT-style system design.

The implementation follows the repository's Clean Architecture / DDD rules: the domain is framework-free, application code depends only on the domain, input adapters own HTTP concerns, and output adapters implement storage/model-provider gateways.

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
- `conversation-infrastructure-output`: in-memory conversation store and deterministic development model adapter.
- `conversation-bootstrap`: Spring Boot composition root.
- `conversation-architecture`: ArchUnit dependency-rule tests.

### 2. Durable, retry-safe turns

`POST /messages` now requires an `Idempotency-Key` header. The application creates a durable `Generation` with an explicit `PENDING`, `COMPLETED`, or `FAILED` state and persists the user message plus pending generation through one atomic `TurnRepository.begin(...)` boundary before invoking the model.

Retry semantics are intentionally explicit:

- Repeating a completed request with the same key and content returns the original logical turn without another model call.
- Reusing a key with different content returns a conflict.
- If the provider fails, the user message remains durable and the generation becomes `FAILED`; retrying the same key reuses that user message rather than appending another one.
- A retry of an unresolved `PENDING` generation may invoke the provider again. This is deliberate at-least-once inference behavior: durable conversational state is idempotent, while exactly-once external inference would require provider-side idempotency or a stronger provider protocol.

The in-memory adapter demonstrates the transaction/outbox seam by implementing `ConversationRepository` and `TurnRepository` over the same store. A production adapter can map `begin(...)` and `complete(...)` to a database transaction plus outbox row without changing the application or domain API.

### HTTP endpoints

```text
POST /v1/conversations
GET  /v1/conversations/{conversationId}
POST /v1/conversations/{conversationId}/messages
     Idempotency-Key: <client-generated-key>
```

Inference is still executed synchronously after the durable handoff. The model adapter is intentionally deterministic so the vertical slice is runnable without external credentials.

## Why these slices first

The key boundary is not a particular LLM vendor or datastore. It is the contract between conversational state and inference. Establishing durable turn identity before introducing queues and streaming prevents request retries, worker retries, and provider failures from creating duplicate conversational state.

## Next implementation slices

1. Split inference from the request lifecycle: inference jobs, admission control/backpressure, cancellation, and token streaming over SSE.
2. Add model routing: model capability/cost policy, provider health, fallback policy, and per-tenant/user quotas.
3. Add context assembly: token budgeting, recent-turn windowing, summaries, retrieval, and long-term memory as explicit context sources.
4. Add tools: typed tool calls, isolated execution, authorization, deadlines, result persistence, and continuation of the same turn.
5. Replace the development store with durable conversation/message persistence plus cache/read models where they materially improve latency.
6. Add observability around time-to-first-token, tokens/sec, queue delay, provider latency, retries, cancellations, and end-to-end turn latency.

## Build

```bash
mvn -f chatgpt/pom.xml verify
mvn -f chatgpt/pom.xml -pl conversation/bootstrap -am spring-boot:run
```
