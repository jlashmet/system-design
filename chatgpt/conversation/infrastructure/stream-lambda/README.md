# DynamoDB Streams inference handoff

In Dynamo storage mode, inference dispatch is driven by the durable generation write rather than by an application outbox or a post-commit SQS call.

```text
POST /messages
  -> DynamoDB transaction
       - user message
       - PENDING generation
       - idempotency mapping
  -> DynamoDB Stream (NEW_IMAGE)
  -> DynamoGenerationStreamHandler Lambda
  -> SQS inference queue
  -> leased/fenced inference worker
```

The Lambda accepts only DynamoDB Stream `INSERT` records whose new image has `entityType=GENERATION` and `status=PENDING`. The CloudFormation event-source mapping applies the same filter so unrelated conversation-table changes normally never invoke the function.

Delivery is intentionally at-least-once. The event-source mapping enables `ReportBatchItemFailures`, and the handler returns failed DynamoDB Stream sequence numbers instead of failing the whole invocation when one SQS publication fails. Successful records are omitted from the failure response and can advance the stream checkpoint. DynamoDB Streams ordering still means records at or after the earliest reported failure may be replayed by Lambda; therefore duplicate SQS jobs remain expected and safe because generation processing uses leased/fenced claims.

A failed target record without a DynamoDB Stream sequence number cannot be identified safely in a partial-batch response, so the handler fails the invocation in that malformed case rather than acknowledging work it cannot name for retry.

## Failure retention and replay

The event-source mapping uses bounded retries instead of allowing a poison record to block a shard until stream retention expires. `MaximumRetryAttempts` defaults to `10`, and `MaximumRecordAgeInSeconds` defaults to `21600` (six hours). Both are CloudFormation parameters.

A separate SQS queue ARN is required through `StreamFailureQueueArn`. When Lambda exhausts the retry/age policy, the event-source mapping writes failure metadata to that queue. Operators should alarm on that queue and replay the referenced stream range while the DynamoDB Stream records are still retained. The failure destination is not the inference DLQ: it represents a failure to hand a committed generation from DynamoDB Streams into the inference queue, while the inference DLQ represents a job that reached workers but exhausted worker/provider retries.

## DynamoDB table requirement

Enable DynamoDB Streams on the conversation table with `NEW_IMAGE`. The stream must be enabled before deploying the event-source mapping.

## Lambda artifact

`mvn verify` builds the module and the shade plugin attaches a Lambda artifact named like:

```text
conversation-infrastructure-stream-lambda-0.0.1-SNAPSHOT-lambda.jar
```

Handler:

```text
com.systemdesign.chatgpt.conversation.infrastructure.streamlambda.DynamoGenerationStreamHandler::handleRequest
```

Runtime: Java 21.

The Lambda requires environment variable `INFERENCE_QUEUE_URL` and IAM permission to read the DynamoDB Stream and call `sqs:SendMessage` on both the inference queue and stream-consumer failure queue. `template.yaml` defines the function, role, filter, partial-batch response mode, bounded retry policy, failure destination, and event-source mapping while keeping the existing stream, queues, and artifact bucket external parameters.

`TRIM_HORIZON` is the template default when first attaching the consumer so retained stream records are not skipped. `LATEST` is available as an explicit deployment choice. DynamoDB Streams retention is finite, so production monitoring should alert on Lambda iterator age, errors/throttles, partial-batch failures, the stream-consumer failure queue, and SQS inference backlog.

## Local development

Memory storage mode has no DynamoDB Stream, so `InferenceDispatch` is implemented by `ImmediateInferenceDispatch` and sends directly to the configured in-memory queue after the durable in-process turn write. Dynamo storage mode binds `InferenceDispatch` to a no-op; the stream/Lambda path is the sole initial-dispatch mechanism.

## Tests

The unit tests verify defensive event filtering, per-record SQS failure reporting, continued processing after an individual failure, and malformed-record behavior. `DynamoGenerationStreamHandlerIT` uses Floci/Testcontainers and a real emulated SQS queue, invokes the real handler with a synthetic DynamoDB Stream record, then decodes the resulting message with the same `InferenceJobCodec` used by `SqsInferenceJobQueue`.
