# Tasks

Scope note: this file covers the `izgw-hub` PR only. The Configuration Console PR is
tracked on IGDD-3599 and ships in the same release. Group 7 names its work so the
release is not cut without it.

Verification note: `ApplicationTests` and `SwaggerUiVersionIntegrationTests` use
`useMainMethod = ALWAYS`, so they boot the whole application and need live AWS
credentials. `SPRING_DATABASE=jpa` does not avoid the DynamoDB client. A developer
without those credentials can verify `mvn compile` and the 73 unit tests, and no more.

RISK: group 1 changes Spring bean wiring, and a context-startup break is exactly what
`ApplicationTests` catches. Task 7.3 runs those two tests with credentials. Do not open
the pull request until 7.3 passes.

## 1. Extract the messaging component

- [x] 1.1 Change `DbControllerConfiguration.refresh()` from private to public in `src/main/java/gov/cdc/izgateway/db/DbController.java` and verify `mvn clean package` compiles
- [x] 1.2 Convert `src/main/java/gov/cdc/izgateway/db/RefreshQueueService.java` to a Spring `@Service` whose constructor takes `DbControllerConfiguration`, `EndpointStatusService`, `ApiKeyPrincipalProvider` and `IHostRepository`, and verify no constructor parameter names a controller
- [x] 1.3 Move queue creation and listener start from the constructor into a `@PostConstruct` method and verify the application starts with exactly one "Started refresh listener on queue" record
- [x] 1.4 Replace `new RefreshQueueService(...)` at `DbController.java:231` with constructor injection and verify `mvn compile` succeeds and no constructor cycle exists between the bean and either controller
- [x] 1.5 Add a unit test asserting that only one listener thread starts when both controllers are wired, in `src/test/java/gov/cdc/izgateway/db/RefreshQueueServiceTests.java`

## 2. Apply commands in process, never over HTTP

- [x] 2.1 Add a package-private method on the messaging service that applies a command locally by calling `DbControllerConfiguration.refresh()`, `EndpointStatusService.resetCircuitBreakers()` or `resetCircuitBreakerById(id)`, and `ApiKeyPrincipalProvider.evictCredential(jti)`, and verify a unit test covers each branch
- [x] 2.2 Change `handleRefreshRequest` to call that method instead of `dbController.resetEndpoint(...)` and verify no call path reaches `callEndpoint`
- [x] 2.3 Change `DbController.getRefreshed` to call that method for the local instance instead of `resetEndpoint(me, eventId)` and verify the same
- [x] 2.4 Delete `resetEndpoint` and `callEndpoint` from `DbController.java` and verify `mvn clean package` compiles with no unused-import warnings
- [x] 2.5 Add a unit test asserting that applying a received command sends no further SQS message, in `RefreshQueueServiceTests.java` — this is the recursion guard from design D4

## 3. Generalise the command message

- [x] 3.1 Add `refreshConfig`, `resetBreakers` and `destId` to the `RefreshRequest` record, keeping the body string `RefreshRequest` and every existing attribute name, and verify `toMap` emits the old attributes unchanged
- [x] 3.2 Make `fromMessage` default `refreshConfig` to true and `destId` to null when the attributes are absent, and verify a unit test parses a message built by the previous format — this is the rolling-deploy compatibility from design D3
- [x] 3.3 Change `RefreshResponse` to carry a real status instead of a hardcoded `OK`, and verify a unit test asserts a failure status survives the round trip
- [x] 3.4 Add a unit test covering both message directions of design D3: new attributes read by old parsing rules, and absent attributes read by new parsing rules

## 4. Fan the reset endpoints out

- [x] 4.1 Inject the messaging service into `src/main/java/gov/cdc/izgateway/status/StatusController.java` and verify the application context loads
- [x] 4.2 Change `resetCircuitBreakers()` to apply locally, distribute to every other instance, wait for replies, and return the envelope `{ destinations, hosts, regions }`, and verify a unit test with a mocked `SqsClient` asserts one message per remote host
- [x] 4.3 Change `resetCircuitBreakerById(id)` the same way, keeping every existing field and adding a `hosts` member, and verify a unit test asserts `destId` and `status` are unchanged
- [x] 4.4 Keep the 404 for an unknown destination and verify no SQS message is sent in that case
- [x] 4.5 Add unit tests for the timeout path and the partial-failure path, asserting HTTP 200 with the silent host marked `Timed Out`
- [x] 4.6 Add a `regions` member to both responses giving one status per region, drawn from `complete`, `partial` and `failed`, and verify a unit test asserts each of the three states
- [x] 4.7 Add a unit test asserting that the `regions` member carries no host name and no instance count — this is the privacy constraint from design D5
- [x] 4.8 Update the Swagger `@Operation` text on both endpoints — it reads "on this host" today — and verify the text at `/swagger/ui.html` names every instance

## 5. Report outcomes honestly

- [x] 5.1 Report `No Queue` separately from `Send Failure` by catching `QueueDoesNotExistException` in `sendRefreshMessage`, and verify a unit test asserts the two outcomes differ
- [x] 5.2 Stop writing `OK (Local)` into the results map before the local attempt in `getRefreshed`, and write the real outcome after it, and verify a unit test asserts a local failure is reported as a failure
- [x] 5.3 Add a unit test asserting that no instance is reported successful without a confirming reply — this is the acceptance criterion from the `circuit-breaker-reset` spec

## 6. Log the SQS traffic

- [x] 6.1 Add an INFO record on a successful send in `sendRefreshMessage` carrying `eventType=SQS_SEND`, `direction`, `queueName`, `targetHost`, `targetRegion`, `messageType` and `keyId`, and verify a `MemoryAppender` test asserts each field
- [x] 6.2 Promote the two DEBUG "Received message" records to INFO with `eventType=SQS_RECEIVE` and the same field set, and verify a `MemoryAppender` test asserts they are emitted at the configured `gov.cdc.izgateway` level
- [x] 6.3 Add an INFO record on a successful reply send in `sendRefreshResponse` and verify a test asserts it appears on the replying instance
- [x] 6.4 Fix the dropped payload at `RefreshQueueService.java:360` — the format string has no placeholder — and verify the rendered message carries the request detail
- [x] 6.5 Add one INFO summary record when a distributed operation ends, carrying `eventType=SQS_OPERATION_COMPLETE`, the instance count and the failure count, and verify a test asserts the counts
- [x] 6.6 Name the credential identifier `keyId`, matching `ApiKeyAuditLogger`, and verify a test asserts the field name
- [x] 6.7 Security review checkpoint: CONFIRMED 2026-10-22. Logging the unmasked `keyId` matches established practice and adds no new kind of disclosure. `jti` is the DynamoDB sort key of `ApiKeyCredential`, a lookup handle rather than a secret: authorization is read server-side from the record it points at (`ApiKeyCredential.java:23`), so holding one authenticates nothing. The value already reaches Elasticsearch from 15 pre-existing call sites: `ApiKeyAuditLogger:62,91` (INFO), `GracePeriodRevocationScheduler:133` (WARN), `ApiKeyCredentialRepository:167,185` (WARN), and ten raw-text records in `ApiKeyPrincipalProvider`. Decisive case: `ApiKeyPrincipalProvider.evictCredential`, the method this change calls, already ends with `log.info("Evicted credential cache entries for jti={}", jti)`. Masking would be a separate and larger ticket across all 15 sites, and masking only here would break the correlation the shared `keyId` field provides
- [x] 6.8 Document the new log records and their fields in `docs/` and verify an operator can follow one operation across instances using only the documented fields

## 7. Integration and release

- [x] 7.1 Run `mvn compile` and verify BUILD SUCCESS
- [x] 7.2 Run `mvn test -Dtest='RefreshQueueServiceTests,StatusControllerResetTests,RefreshQueueLoggingTests'` and verify every test passes. Then run plain `mvn test` and verify the result matches the baseline on `develop`. Without AWS credentials that run stops at `SwaggerUiVersionIntegrationTests` before it reaches the new classes, which is the pre-existing limitation recorded at the top of this file. The pipeline runs the whole suite
- [x] 7.3 PASSED in the pipeline, run 1233 and later. `ApplicationTests` 6/6 and `SwaggerUiVersionIntegrationTests` 2/2. The log confirms one listener, not two: two queues created and a single "Started refresh listener on queue" record. The bean rewiring from group 1 is sound
- [x] 7.4 PASSED in the pipeline. The dependency check reported no new finding at CVSS 7 or above. `site` also ran for the first time and completed, so Javadoc and Checkstyle are clear on the new public members
- [x] 7.5 PASSED on dev 2026-10-22. Four instances answered, three in us-east-1 and one in us-west-2, every one `OK`. Both regions read `complete`. Two destinations re-threw after the reset (`dex_old`, `ndlp-stg`); both are genuinely failing, so the status checker re-throwing them is correct
- [x] 7.6 PASSED on dev 2026-10-22. With one of four tasks stopped, that instance read `Timed Out` and never `OK`, which is defect 3 fixed. Its region read `partial` while the other read `complete`, so a failure in one region does not contaminate another. `Timed Out` rather than `No Queue` shows the queue outlived the task, the orphaned-queue case. That call took the full 15 seconds, which confirms why the Console timeout must exceed it
- [x] 7.7 PASSED on dev 2026-10-22, `eventId 10200018060.3932`. Five records under one correlation id across two hosts and two regions: the sender's send and receive, the receiver's receive and reply, and the summary reading `instances 2, succeeded 2, failed 0` with both regions `complete`. The correlation id survives the SQS hop, so one query traces an operation end to end. Round trip 343 ms cross-region against a 15 second ceiling. NOTE: a cross-host trace can show a receive a few milliseconds before its send, which is clock skew between regions rather than out-of-order delivery
Release note, not a task: the Configuration Console PR for IGDD-3599 merges before this one. The Hub change alone makes the Reset All button report failure when an instance is slow. That work is tracked on the ticket and in the Console's own OpenSpec change, so it does not gate Hub verification.
- [x] 7.8 Write the pull request title so that it names the behavior change on both reset endpoints. Do not edit `RELEASE_NOTES.md` by hand: `_release_common.yml` generates a section per release from the merged pull request titles, so a hand-written entry is overwritten. Verify the title reads as a release note on its own

## Summary

| Group | Work | Hours | Depends on |
|---|---|---|---|
| 1 | Extract the messaging component | 3–4 | — |
| 2 | Apply commands in process | 2–3 | 1 |
| 3 | Generalise the command message | 2–3 | 1 |
| 4 | Fan the reset endpoints out | 3–4 | 2, 3 |
| 5 | Report outcomes honestly | 2 | 3 |
| 6 | Log the SQS traffic | 3–4 | 3 |
| 7 | Integration and release | 2–3 | 4, 5, 6 |

Total: 17–24 hours.

No DynamoDB schema change, so no coordination with the infrastructure team is needed.
No BCFIPS provider or keystore change, so no FIPS review is needed. Group 6 carries the
one security review checkpoint, for credential identifiers in log output.
