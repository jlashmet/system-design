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

`POST /messages` requires an `Idempotency-Key` header. The application creates a durable `Generation` and persists the user message plus pending generation through one atomic `TurnRepository.begin(...)` boundary before inference work can start.

Retry semantics are intentionally explicit:

- Repeating a completed request with the same key and content returns the original logical generation.
- Reusing a key with different content returns a conflict.
- If the provider fails, the user message remains durable and the generation becomes `FAILED`; retrying the same key reuses that user message rather than appending another one.
- Durable conversational state is idempotent while external inference remains at-least-once unless the provider itself supplies stronger idempotency guarantees.

### 3. Asynchronous inference boundary and generation events

Message submission no longer invokes the model in the HTTP request lifecycle. `POST /messages` persists the user message and generation, enqueues the generation ID through an `InferenceJobQueue` port, and returns `202 Accepted` immediately.

A separate `ProcessGenerationHandler` owns provider inference. Workers atomically claim a generation (`PENDING`/`FAILED` -> `RUNNING`) before invoking the provider, so duplicate queue delivery cannot produce duplicate assistant messages. The development composition uses an in-memory queue and a scheduled worker; a production adapter can replace these with Kafka/SQS/Pulsar or another durable queue without changing the use cases.

Clients can inspect or follow generation state independently of the original request:

```text
GET /v1/conversations/{conversationId}/generations/{generationId}
GET /v1/conversations/{conversationId}/generations/{generationId}/events
    Accept: text/event-stream
```

The model gateway now has a streaming callback in addition to whole-completion inference. `ProcessGenerationHandler` publishes each model delta through a `GenerationEventBus`, and the SSE endpoint subscribes directly to those events. This removes the previous status-polling loop from the streaming edge and allows clients to receive `delta`, `completed`, and `failed` events as inference happens.

### HTTP endpoints

```text
POST /v1/conversations
GET  /v1/conversations/{conversationId}
POST /v1/conversations/{conversationId}/messages
     Idempotency-Key: <client-generated-key>
GET  /v1/conversations/{conversationId}/generations/{generationId}
GET  /v1/conversations/{conversationId}/generations/{generationId}/events
```

The model adapter is intentionally deterministic so the vertical slices remain runnable without external credentials; its streaming implementation emits multiple chunks so the SSE path is testable locally.

## Why these slices first

The key boundary is not a particular LLM vendor or datastore. It is the contract between conversational state and inference. Durable turn identity plus an independent generation lifecycle means request retries, worker retries, provider failures, and streaming transports can evolve without duplicating conversational truth.

## Next implementation slices

1. Complete the async inference slice: cancellation, queue admission control/backpressure, and worker retry/dead-letter policy.
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
