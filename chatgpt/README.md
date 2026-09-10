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

## Implemented first slice

The initial slice establishes the end-to-end seam before adding distributed infrastructure:

- `conversation-domain`: conversation aggregate, ordered messages, repository port, and model gateway port.
- `conversation-application`: create/get/send-message handlers.
- `conversation-api`: transport DTOs with no internal dependencies.
- `conversation-infrastructure-input`: REST endpoints and error mapping.
- `conversation-infrastructure-output`: in-memory conversation store and deterministic development model adapter.
- `conversation-bootstrap`: Spring Boot composition root.
- `conversation-architecture`: ArchUnit dependency-rule tests.

### HTTP endpoints

```text
POST /v1/conversations
GET  /v1/conversations/{conversationId}
POST /v1/conversations/{conversationId}/messages
```

`POST /messages` currently executes one complete user -> model -> assistant turn synchronously. The model adapter is intentionally deterministic so the vertical slice is runnable without external credentials.

## Why this slice first

The key boundary is not a particular LLM vendor or datastore. It is the contract between conversational state and inference. Once that contract is stable, the synchronous adapters can be replaced independently by production-scale implementations.

## Next implementation slices

1. Make a turn durable and retry-safe: client idempotency key, explicit generation state, transactional/outbox handoff, and retry semantics.
2. Split inference from the request lifecycle: inference jobs, admission control/backpressure, cancellation, and token streaming over SSE.
3. Add model routing: model capability/cost policy, provider health, fallback policy, and per-tenant/user quotas.
4. Add context assembly: token budgeting, recent-turn windowing, summaries, retrieval, and long-term memory as explicit context sources.
5. Add tools: typed tool calls, isolated execution, authorization, deadlines, result persistence, and continuation of the same turn.
6. Replace the development store with durable conversation/message persistence plus cache/read models where they materially improve latency.
7. Add observability around time-to-first-token, tokens/sec, queue delay, provider latency, retries, cancellations, and end-to-end turn latency.

## Build

```bash
mvn -f chatgpt/pom.xml verify
mvn -f chatgpt/pom.xml -pl conversation/bootstrap -am spring-boot:run
```
