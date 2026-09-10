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

New generations are admitted through an `InferenceQuota` port before durable turn creation. The development adapter provides an idempotency-aware fixed one-minute window keyed by user and request key (`chatgpt.inference.requests-per-minute`, default `60`). Quota exhaustion returns `429 Too Many Requests` with `Retry-After: 60`. Existing idempotent generations do not consume quota again.

### 8. Generation-scoped context assembly and token budgeting

The worker no longer sends the whole conversation directly to the model. A `ContextAssembler` owns the inference context, with a `TokenEstimator` port separating model-token accounting from context policy.

`BudgetedContextAssembler` anchors the context at the generation's own durable user message, so a delayed asynchronous worker never sees messages submitted after that generation. It reserves system context first, then fills the remaining input budget with a contiguous suffix of the most recent eligible non-system messages. The current input budget is configurable with `chatgpt.context.max-input-tokens` (default `8192`). If required system context or the current user message alone cannot fit, context assembly fails explicitly instead of silently truncating required input.

The development `HeuristicTokenEstimator` uses a deliberately simple estimate; a production model-family tokenizer can replace it behind the same port. This context seam is also where summaries, retrieval results, and long-term memory sources will be composed next.

### HTTP endpoints

```text
POST /v1/conversations
GET  /v1/conversations/{conversationId}
POST /v1/conversations/{conversationId}/messages
     Idempotency-Key: <client-generated-key>
     { "content": "...", "requiredCapabilities": ["vision", "tool_calling"] }
GET  /v1/conversations/{conversationId}/generations/{generationId}
GET  /v1/conversations/{conversationId}/generations/{generationId}/events
POST /v1/conversations/{conversationId}/generations/{generationId}/cancel
```

The development composition uses in-memory storage, quota, queue/event adapters, a heuristic token estimator, and a deterministic model so the vertical slices remain runnable without external credentials.

## Next implementation slices

1. Extend context assembly with summaries, retrieval, and long-term memory as explicit prioritized context sources.
2. Add tools: typed tool calls, isolated execution, authorization, deadlines, result persistence, and continuation of the same turn.
3. Replace the development store with durable conversation/message persistence plus cache/read models where they materially improve latency.
4. Add observability around time-to-first-token, tokens/sec, context-token composition, queue delay, provider latency, routing/fallback decisions, quota rejection, retries, cancellations, and end-to-end turn latency.

## Build

```bash
mvn -f chatgpt/pom.xml verify
mvn -f chatgpt/pom.xml -pl conversation/bootstrap -am spring-boot:run
```
