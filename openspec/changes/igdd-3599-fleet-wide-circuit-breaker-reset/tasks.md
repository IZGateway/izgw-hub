# Tasks

Scope note: this file covers the `izgw-hub` PR only. The Configuration Console PR is
tracked on IGDD-3599 and ships in the same release. Group 7 names its work so the
release is not cut without it.

## 1. Extract the messaging component

- [ ] 1.1 Change `DbControllerConfiguration.refresh()` from private to public in `src/main/java/gov/cdc/izgateway/db/DbController.java` and verify `mvn clean package` compiles
- [ ] 1.2 Convert `src/main/java/gov/cdc/izgateway/db/RefreshQueueService.java` to a Spring `@Service` whose constructor takes `DbControllerConfiguration`, `EndpointStatusService`, `ApiKeyPrincipalProvider` and `IHostRepository`, and verify no constructor parameter names a controller
- [ ] 1.3 Move queue creation and listener start from the constructor into a `@PostConstruct` method and verify the application starts with exactly one "Started refresh listener on queue" record
- [ ] 1.4 Replace `new RefreshQueueService(...)` at `DbController.java:231` with constructor injection and verify `mvn test -Dtest=ApplicationTests` passes
- [ ] 1.5 Add a unit test asserting that only one listener thread starts when both controllers are wired, in `src/test/java/gov/cdc/izgateway/db/RefreshQueueServiceTests.java`

## 2. Apply commands in process, never over HTTP

- [ ] 2.1 Add a package-private method on the messaging service that applies a command locally by calling `DbControllerConfiguration.refresh()`, `EndpointStatusService.resetCircuitBreakers()` or `resetCircuitBreakerById(id)`, and `ApiKeyPrincipalProvider.evictCredential(jti)`, and verify a unit test covers each branch
- [ ] 2.2 Change `handleRefreshRequest` to call that method instead of `dbController.resetEndpoint(...)` and verify no call path reaches `callEndpoint`
- [ ] 2.3 Change `DbController.getRefreshed` to call that method for the local instance instead of `resetEndpoint(me, eventId)` and verify the same
- [ ] 2.4 Delete `resetEndpoint` and `callEndpoint` from `DbController.java` and verify `mvn clean package` compiles with no unused-import warnings
- [ ] 2.5 Add a unit test asserting that applying a received command sends no further SQS message, in `RefreshQueueServiceTests.java` — this is the recursion guard from design D4

## 3. Generalise the command message

- [ ] 3.1 Add `refreshConfig`, `resetBreakers` and `destId` to the `RefreshRequest` record, keeping the body string `RefreshRequest` and every existing attribute name, and verify `toMap` emits the old attributes unchanged
- [ ] 3.2 Make `fromMessage` default `refreshConfig` to true and `destId` to null when the attributes are absent, and verify a unit test parses a message built by the previous format — this is the rolling-deploy compatibility from design D3
- [ ] 3.3 Change `RefreshResponse` to carry a real status instead of a hardcoded `OK`, and verify a unit test asserts a failure status survives the round trip
- [ ] 3.4 Add a unit test covering both message directions of design D3: new attributes read by old parsing rules, and absent attributes read by new parsing rules

## 4. Fan the reset endpoints out

- [ ] 4.1 Inject the messaging service into `src/main/java/gov/cdc/izgateway/status/StatusController.java` and verify the application context loads
- [ ] 4.2 Change `resetCircuitBreakers()` to apply locally, distribute to every other instance, wait for replies, and return the envelope `{ destinations, hosts }`, and verify a unit test with a mocked `SqsClient` asserts one message per remote host
- [ ] 4.3 Change `resetCircuitBreakerById(id)` the same way, keeping every existing field and adding a `hosts` member, and verify a unit test asserts `destId` and `status` are unchanged
- [ ] 4.4 Keep the 404 for an unknown destination and verify no SQS message is sent in that case
- [ ] 4.5 Add unit tests for the timeout path and the partial-failure path, asserting HTTP 200 with the silent host marked `Timed Out`
- [ ] 4.6 Update the Swagger `@Operation` text on both endpoints — it reads "on this host" today — and verify the text at `/swagger/ui.html` names every instance

## 5. Report outcomes honestly

- [ ] 5.1 Report `No Queue` separately from `Send Failure` by catching `QueueDoesNotExistException` in `sendRefreshMessage`, and verify a unit test asserts the two outcomes differ
- [ ] 5.2 Stop writing `OK (Local)` into the results map before the local attempt in `getRefreshed`, and write the real outcome after it, and verify a unit test asserts a local failure is reported as a failure
- [ ] 5.3 Add a unit test asserting that no instance is reported successful without a confirming reply — this is the acceptance criterion from the `circuit-breaker-reset` spec

## 6. Log the SQS traffic

- [ ] 6.1 Add an INFO record on a successful send in `sendRefreshMessage` carrying `eventType=SQS_SEND`, `direction`, `queueName`, `targetHost`, `targetRegion`, `messageType` and `keyId`, and verify a `MemoryAppender` test asserts each field
- [ ] 6.2 Promote the two DEBUG "Received message" records to INFO with `eventType=SQS_RECEIVE` and the same field set, and verify a `MemoryAppender` test asserts they are emitted at the configured `gov.cdc.izgateway` level
- [ ] 6.3 Add an INFO record on a successful reply send in `sendRefreshResponse` and verify a test asserts it appears on the replying instance
- [ ] 6.4 Fix the dropped payload at `RefreshQueueService.java:360` — the format string has no placeholder — and verify the rendered message carries the request detail
- [ ] 6.5 Add one INFO summary record when a distributed operation ends, carrying `eventType=SQS_OPERATION_COMPLETE`, the instance count and the failure count, and verify a test asserts the counts
- [ ] 6.6 Name the credential identifier `keyId`, matching `ApiKeyAuditLogger`, and verify a test asserts the field name
- [ ] 6.7 Security review checkpoint: confirm with the reviewer that logging the unmasked `keyId` matches the existing `ApiKeyAuditLogger` treatment, and that no other command field is sensitive — required because this task group adds credential identifiers to log output
- [ ] 6.8 Document the new log records and their fields in `docs/` and verify an operator can follow one operation across instances using only the documented fields

## 7. Integration and release

- [ ] 7.1 Run `mvn clean package` and verify every test passes with `SPRING_DATABASE=jpa`
- [ ] 7.2 Run `mvn verify -DskipTests` and verify the OWASP check reports no new finding at CVSS 7 or above
- [ ] 7.3 Scale dev region 2 to one task by hand, post to `/rest/reset`, and verify the `hosts` map holds two entries, one per region
- [ ] 7.4 On dev, stop one task and post to `/rest/reset`, and verify the stopped instance reports `No Queue` or `Timed Out` rather than success
- [ ] 7.5 On dev, search Elastic for the operation `eventId` and verify the send, receive, reply and summary records all appear
- [ ] 7.6 Confirm the Configuration Console PR for IGDD-3599 is merged and in the same release — the Hub change alone makes the Reset All button report failure when an instance is slow
- [ ] 7.7 Add a `RELEASE_NOTES.md` entry naming the behavior change on both reset endpoints and verify the entry states that a reset now reaches every instance

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
