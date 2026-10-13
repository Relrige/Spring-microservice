---
status: TODO
service: catalog-service
---
# Non-Blocking Retries and Dead-Letter Topic for Stock Events (`@RetryableTopic`)

## Context
[Task 18](18-consume-stock-events.md) allows either a dead-letter topic or "skip and log" for failed stock events. The usual Spring Kafka default is a container `DefaultErrorHandler`, which retries **in place**: the partition waits during the back-off, then the record is recovered. This task settles the choice:
- stock events get **non-blocking retries** through `@RetryableTopic`;
- a record that still fails ends in a **dead-letter topic (DLT)**, so it is neither lost nor blocking.

**Blocking vs. non-blocking retries, the trade-off:**
- *Blocking* (`DefaultErrorHandler`): keeps per-key order for free. But one failing record stalls its whole partition (every product on it) for the whole back-off.
- *Non-blocking* (`@RetryableTopic`): the failed record is republished to `…-retry-N` topics and consumed again after a delay, while the main partition continues. Other products are not delayed, but events of **one** product can now be applied out of order. That is acceptable only because:
  - the version guard of [task 23](23-stock-event-version-guard.md) discards a stale event that arrives late;
  - the processed-events table of [task 24](24-idempotent-stock-event-consumer.md) discards a copy whose effect was already stored.

This task depends on both.
*References:* [Consume stock events](18-consume-stock-events.md), [Version guard](23-stock-event-version-guard.md), [Idempotent consumer](24-idempotent-stock-event-consumer.md), [Spring for Apache Kafka — Non-Blocking Retries](https://docs.spring.io/spring-kafka/reference/retrytopic.html)

## Acceptance Criteria
- [ ] The stock-event `@KafkaListener` is annotated with `@RetryableTopic`. All values come from configuration with sensible defaults, and the chosen values are documented:
  - attempts (for example 4: the first try plus 3 retries);
  - exponential back-off (for example 1 s, then ×2);
  - topic suffixes.
- [ ] **Topic naming and ownership:**
  - The retry and dead-letter topics belong to Catalog, as this consumer's error handling. Inventory never reads them.
  - Use consumer-specific suffixes (for example `inventory.stock-events-catalog-retry-0..n` and `inventory.stock-events-catalog-dlt`), so that another consumer group adding `@RetryableTopic` on the same source topic cannot collide.
  - Created with **3 partitions** (same as the source topic, so the key → partition mapping stays) and replication factor 1. Use the annotation's `numPartitions`/`replicationFactor` or explicit `NewTopic` beans. Broker auto-creation is disabled in tests, so creation must be explicit.
- [ ] **Not retryable**, straight to the DLT:
  - deserialization failures (`DeserializationException` from the `ErrorHandlingDeserializer`);
  - conversion/validation failures (missing `eventId`, `productId` or `version`).

  Use a dedicated exception type for validation failures and add it to the `@RetryableTopic` exclusions, or rely on the framework defaults; verify which exceptions the defaults cover. Business outcomes are still **not** errors: unknown product, unknown `eventType`, stale version and duplicate are acknowledged, as in tasks 18, 23 and 24.
- [ ] **Retryable:** everything else, for example the database being briefly unavailable.
- [ ] A `@DltHandler` logs each dead-lettered record at ERROR with the original topic, partition, offset, key, `eventId` if readable, and the exception message from the DLT headers. A counter metric for DLT records, tagged with the exception class, is a plus.
- [ ] For a deserialization failure, the DLT record contains the **original bytes** of the value (not `null`, not a JSON-re-serialized or base64 form). Verify this. It usually needs the dead-letter producer to send `byte[]` values with a `ByteArraySerializer`, for example via a `DelegatingByTypeSerializer`, while normal events are sent as JSON.
- [ ] With 2 replicas, the retry-topic listeners join the same consumer group (`catalog-service`), so each retry is handled once.
- [ ] If task 18 was implemented with a container-level `DefaultErrorHandler` and log-and-skip recovery for this listener, that path is removed or limited to other listeners; one listener has exactly one retry policy.
- [ ] **Integration tests** (Testcontainers Kafka + PostgreSQL, the existing test setup; `@EmbeddedKafka` is an acceptable alternative). Use short back-offs from a test profile:
  - a malformed payload goes **directly** to the DLT (no retry topic visited), with the original bytes and the exception headers;
  - a payload missing `version` goes directly to the DLT;
  - processing that always fails (for example a test bean that makes the service throw for one specific `productId`) passes through every retry topic and ends in the DLT after exactly the configured number of attempts;
  - while that record is retrying, a valid event for **another product on the same partition** is applied without waiting (the non-blocking property);
  - a transient failure (fails once, then succeeds) is applied exactly once and nothing reaches the DLT;
  - out of order through retries: version 2 of a product fails once, version 3 is applied meanwhile, and the retried version 2 is discarded as stale. Final state = version 3.

## Technical Notes / Constraints
- **Why the DLT test matters:** it is a required deliverable of the course assignment ("an integration test that verifies an unprocessed event goes to the DLT"). Read the DLT with a plain test consumer (`KafkaTestUtils` or a `KafkaConsumer` with a random group), not with the application's listener.
- Spring Kafka implements the delay by pausing the retry-topic partition until the record is due. The actual delay is therefore "at least" the back-off, and depends on `max.poll.interval.ms` and the poll timeout. Keep back-offs well below `max.poll.interval.ms`.
- Optional hybrid: Spring supports a few fast **blocking** retries before going non-blocking (`configureBlockingRetries` in a `RetryTopicConfigurationSupport` subclass). Not needed here; mention it as an alternative.
- **DLT replay is out of scope.** Document the manual procedure in the task notes: inspect the record with `kafka-console-consumer.sh` and print headers; republish to the source topic after the cause is fixed. The version guard and the processed-events table make a replay safe.
- Deserialization: Catalog uses `ErrorHandlingDeserializer` → `JacksonJsonDeserializer` with the target type set per listener (see task 18 "Starting Point"). Check that the retry-topic containers get the same deserializer settings.
- Keep the retry configuration out of the listener method body: annotation attributes with property placeholders, or a `RetryTopicConfiguration` bean.

## Deviations from Spec / Earlier Tasks (decided 2026-10-13)
- Task 18's "dead-letter topic **or** skip and log" is settled as **dead-letter topic with non-blocking retries**. Task 18's acceptance criteria are not edited; this task supersedes that choice for the stock-event listener.
- [Catalog spec](../../design/services-specs/catalog-service-spec.md) EP-CAT-EVT-01/02 and the [Event Catalogue](../../design/services-requirements/events-catalogue.md) should list the retry/DLT topics as Catalog-owned at the sprint-end spec update.
