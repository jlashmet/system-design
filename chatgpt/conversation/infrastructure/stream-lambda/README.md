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

Delivery is intentionally at-least-once. If Lambda sends to SQS and then the stream batch retries, SQS can contain duplicate generation jobs. That is safe because generation processing uses leased/fenced claims; only the active claim may append tool transcript, complete, or fail the generation.

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

The Lambda requires environment variable `INFERENCE_QUEUE_URL` and IAM permission to read the DynamoDB Stream and call `sqs:SendMessage` on the inference queue. `template.yaml` defines the function, role, filter, and event-source mapping while keeping the existing stream, queue, and artifact bucket external parameters.

`TRIM_HORIZON` is the template default when first attaching the consumer so retained stream records are not skipped. `LATEST` is available as an explicit deployment choice. DynamoDB Streams retention is finite, so production monitoring should alert on Lambda iterator age, errors/throttles, and SQS backlog before retained records can expire.

## Local development

Memory storage mode has no DynamoDB Stream, so `InferenceDispatch` is implemented by `ImmediateInferenceDispatch` and sends directly to the configured in-memory queue after the durable in-process turn write. Dynamo storage mode binds `InferenceDispatch` to a no-op; the stream/Lambda path is the sole initial-dispatch mechanism.

## Tests

The unit test verifies defensive event filtering. `DynamoGenerationStreamHandlerIT` uses Floci/Testcontainers and a real emulated SQS queue, invokes the real handler with a synthetic DynamoDB Stream record, then decodes the resulting message with the same `InferenceJobCodec` used by `SqsInferenceJobQueue`.
