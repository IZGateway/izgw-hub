# Tasks: igdd-2805-source-attack-lockout

Jira: IGDD-2805

## 0. Configuration

- [x] 0.1 In `src/main/resources/application.yml`, under the existing `hub:` block (next to `access-control:`), add:
  ```yaml
      source-attack-lockout:
          enabled: ${HUB_SOURCE_ATTACK_LOCKOUT_ENABLED:false}
          # Distinct from security.enable-blacklist / security.blacklist.disabled, which govern
          # deny-list *enforcement* in general. This flag governs only whether a detected source
          # attack (SecurityFault code 61) automatically adds the sender to the deny list.
          # See openspec/changes/igdd-2805-source-attack-lockout/design.md.
  ```

## 1. Lockout fix (`access-control` capability)

- [x] 1.1 `AccessControlModelHelper` (`hub/service/accesscontrol/AccessControlModelHelper.java`): add `boolean isExemptFromSourceAttackLockout(String sender);` to the interface.
- [x] 1.2 `OldModelHelper`: implement `isExemptFromSourceAttackLockout` returning `false` unconditionally (feature is new-model-only), with a one-line Javadoc note why (mirrors `canAccessDestination`'s existing precedent).
- [x] 1.3 `NewModelHelper`: add `Map<String, SourceAttackExceptionRecord> sourceAttackExceptionCache` (default `Collections.emptyMap()`), populate it in `refresh()` via `refreshCache(accessControlService.sourceAttackExceptionRepository, SourceAttackExceptionRecord::getSender)`, and implement `isExemptFromSourceAttackLockout(sender)` as `sourceAttackExceptionCache.containsKey(sender)`.
- [x] 1.4 `AccessControlService` (`hub/service/accesscontrol/AccessControlService.java`):
  - Add `@Value("${hub.source-attack-lockout.enabled:false}") private boolean sourceAttackLockoutEnabled;`
  - Add `public void handleSourceAttack(String sender, String reason)`: if disabled → log WARN and return; else if `currentModelHelper.isExemptFromSourceAttackLockout(sender)` → log INFO and return; else → call `addUserToDenyList(sender, reason)` and log ERROR. Use `Markers2.append("sender", sender)` on all three log lines for structured search.
- [x] 1.5 `BaseGatewayController` (`hub/BaseGatewayController.java`): override
  ```java
  @Override
  protected ResponseEntity<FaultMessage> handleFault(Fault fault) {
      if (fault instanceof SecurityFault sf
              && SOURCE_ATTACK_CODE.equals(sf.getCode())
              && sf.getEndpoint() == null) {
          accessControlService.handleSourceAttack(RequestContext.getSourceInfo().getCommonName(), sf.getDiagnostics());
      }
      return super.handleFault(fault);
  }
  ```
  Add `private static final String SOURCE_ATTACK_CODE = "61";` with a Javadoc comment pointing at `SecurityFault.MESSAGE_TEMPLATES[1]` (izgw-core) as the source of truth, and noting the `getEndpoint() == null` guard exists specifically to exclude the outbound/destination-response scan (`MessageSender`), which reaches this same method with a non-null endpoint — see design.md D2b.

## 2. Data model and repository (`source-attack-exception-config` capability)

- [x] 2.1 New `dynamodb/model/SourceAttackExceptionRecord.java`: `extends DynamoDbAudit implements DynamoDbEntity, Serializable` (Lombok `@Data @EqualsAndHashCode(callSuper=false) @AllArgsConstructor @NoArgsConstructor @DynamoDbBean`, matching `DenyListRecord`/`FileType`). Fields: `String sender`, `String reason`, `int environment`. `getPrimaryId()` returns `String.format("%d#%s", environment, sender)`. **No new izgw-core marker interface** (unlike `IDenyListRecord`/`IFileType`) — there is no old-model/migration counterpart for this brand-new capability and nothing outside izgw-hub needs to reference it abstractly, so adding one would be an unused abstraction requiring an izgw-core version bump for no functional benefit. Note this deviation in the class Javadoc.
- [x] 2.2 New `hub/repository/ISourceAttackExceptionRepository.java`: `interface ISourceAttackExceptionRepository<T extends SourceAttackExceptionRecord> extends IRepository<T>` with `T store(T record)`, `void delete(T record)`, `List<T> findAll()` — mirrors `IDenyListRecordRepository`, but bound directly to the concrete izgw-hub class instead of an izgw-core interface (see 2.1).
- [x] 2.3 New `dynamodb/repository/SourceAttackExceptionRepository.java`: `extends DynamoDbRepository<SourceAttackExceptionRecord> implements ISourceAttackExceptionRepository<SourceAttackExceptionRecord>`, constructor `(DynamoDbEnhancedClient client, String tableName)`, `store()` delegates to `super.saveAndFlush()`. `delete(T)` needs no override — `DynamoDbRepository.delete(T entity)` is already `public` (only the `delete(String primaryId)` overload is `protected`), same as `NewModelHelper.unblock()` already calling `denyListRecordRepository.delete(dlr)` directly.
- [x] 2.4 `hub/repository/RepositoryFactory.java`: add `ISourceAttackExceptionRepository<SourceAttackExceptionRecord> sourceAttackExceptionRepository();`.
- [x] 2.5 `dynamodb/DynamoDbRepositoryFactory.java`: implement the new factory method, mirroring the existing `denyListRecordRepository()` lazy-init field/method pair.
- [x] 2.6 `AccessControlService`: inject `final ISourceAttackExceptionRepository<SourceAttackExceptionRecord> sourceAttackExceptionRepository;` in the constructor (mirrors `denyListRecordRepository`), sourced from `factory.sourceAttackExceptionRepository()`.
- [x] 2.7 `AccessControlService`: add admin-facing methods that bypass the old/new model split (new capability is new-model-only, same precedent as `getFileType()` calling `newModelHelper` directly):
  - `public SourceAttackExceptionRecord createSourceAttackException(String sender, String reason)` — validates non-blank `sender`/`reason`, builds+stores a record via `newModelHelper`, refreshes the cache, returns the stored record.
  - `public void deleteSourceAttackException(String sender)` — removes the record and refreshes the cache; no-op (not an error) if absent.
  - `public List<SourceAttackExceptionRecord> listSourceAttackExceptions()` — returns the current cache values.
- [x] 2.8 `NewModelHelper`: small private helpers backing 2.7 (`addSourceAttackException`, `removeSourceAttackException`), following the exact shape of `block(user, reason)`/`unblock(user)`.

## 3. Admin REST API

- [x] 3.1 New `hub/SourceAttackExceptionController.java`: `@RestController`, `@RequestMapping("/rest/sourceAttackExceptions")`, `@RolesAllowed({ Roles.ADMIN })` (narrower than `LogController`'s `ADMIN, OPERATIONS, BLACKLIST` — see design.md "Access Control"):
  - `GET` → `accessControlService.listSourceAttackExceptions()`
  - `POST` (body: `{ "sender": "...", "reason": "..." }`) → `createSourceAttackException`, `400` on blank `sender`/`reason`
  - `DELETE /{sender}` → `deleteSourceAttackException`, `204` regardless of prior existence
- [x] 3.2 Add Swagger `@Operation`/`@ApiResponse` annotations consistent with `LogController`'s style.

## 4. Tests

- [x] 4.1 `AccessControlServiceSourceAttackTests` (new, `src/test/java/gov/cdc/izgateway/hub/service/accesscontrol/`): `handleSourceAttack` — (a) flag disabled → sender not deny-listed; (b) flag enabled, no exception → sender deny-listed with the given reason; (c) flag enabled, exception exists → sender not deny-listed.
- [x] 4.2 Same test class: `createSourceAttackException`/`deleteSourceAttackException`/`listSourceAttackExceptions` round-trip; blank `sender`/`reason` rejected.
- [x] 4.3 `BaseGatewayControllerSourceAttackTests` (new): unit-test `handleFault` directly (no Spring context needed) —
  - code `61`, `endpoint == null` → `accessControlService.handleSourceAttack` invoked (verify via Mockito)
  - code `62` (`userBlacklisted`) → not invoked
  - code `60` (`generalSecurity`) → not invoked
  - **code `61`, `endpoint != null`** (the outbound shape) → **not invoked** — this is the negative test for the wrongful-lockout risk found in review; make it its own clearly-named test (`outboundSourceAttack_doesNotTriggerLockout` or similar), not just a case in a parameterized list, so it can't be silently dropped later.
- [x] 4.4 Split into two, per review (a full SOAP-over-HTTP JUnit harness doesn't exist in this repo — SOAP flows are otherwise only exercised via Newman/Postman against a deployed environment):
  - **4.4a** (`SourceAttackShutoutTests`, `hub/service/accesscontrol/`): proves AC #1 ("shutout", not just "recorded") without any HTTP/SOAP layer. Registers the **real** `HubWSDLController.class` against a **real** `AccessControlRegistry` (not hardcoded roles), deny-lists a sender via `addUserToDenyList`, and asserts `checkAccess(sender, "POST", "/IISHubService")` returns `false`. Regression-proofs the exact risk found in review: `AccessControlService.checkAccess` treats a denied user as still-allowed on any endpoint whose registered roles include `BLACKLIST_ROLE`, so this would fail if that role were ever added to the controller's `@RolesAllowed`.
  - **4.4b** (`HubWSDLControllerSourceAttackTests`, `hub/`): proves the one genuinely uncertain link — that `SoapControllerBase`'s controller-local `@ExceptionHandler(SoapConversionException.class)` (`handleBadXML`) actually wins over the global `ExceptionHandling` `@ControllerAdvice` for a converter-thrown exception, and dispatches virtually into the overridden `handleFault`. Uses `MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new SoapMessageConverter(INBOUND)).setControllerAdvice(new ExceptionHandling())` (the advice registration is mandatory — standalone setup doesn't add it by default, and without it the precedence isn't actually being tested) with a real attack-pattern payload adapted from `testing/scripts/IZGW_2.0_Integration_Test.postman_collection.json`'s `SubmitSingleMessageRequest` example, and verifies `accessControlService.handleSourceAttack(...)` was called via Mockito — sidestepping the need for a real DynamoDB or full Spring context entirely.
- [x] 4.5 `NewModelHelperTests` (existing or new file): exception cache populated on `refresh()`; `isExemptFromSourceAttackLockout` true for a cached sender, false otherwise.
- [x] 4.6 `OldModelHelperTests` (existing or new): `isExemptFromSourceAttackLockout` always `false`.
- [x] 4.7 `SourceAttackExceptionControllerTests` (new): admin can create/list/delete; non-admin request is denied (mirror how other `@RolesAllowed` controllers are tested in this repo).
- [x] 4.8 Ran `mvn test -Dtest='!ApplicationTests'` — 268 tests, 0 failures, 0 errors, 7 skipped (pre-existing `@Disabled` cases), no regressions. `ApplicationTests` (a `@SpringBootTest` that boots a real embedded Tomcat with mTLS) is excluded because it requires `COMMON_PASS`/real SSL keystores per this repo's build prerequisites, which aren't available in this environment — confirmed via `mvn test` with and without `SPRING_DATABASE=jpa`, both failing identically at Tomcat startup (`Unable to start web server`) before any of this change's code runs; this is a pre-existing local-environment limitation, not a regression introduced here.

## 5. Documentation (acceptance criteria: exception config must be documented)

- [x] 5.1 Operator runbook (new `openspec/changes/igdd-2805-source-attack-lockout/runbook.md`, mirroring `igdd-2711-grace-period-revocation/runbook.md`'s structure; relocate to the canonical ops-docs location if one exists, per that same precedent): how to enable `hub.source-attack-lockout.enabled`, how to create/list/remove a sender exception via the new `/rest/sourceAttackExceptions` API, the sender-only limitation (no per-receiver scoping, and why), and the multi-instance cache-propagation-lag note (≤ `refreshPeriod`, default 300s).
- [x] 5.2 `RELEASE_NOTES.md`: entry for IGDD-2805 under the next release section, in the style of the existing "Security Features" entries (see IGDD-1109/IGDD-1185 precedent).
- [x] 5.3 `CONFIGURATION.md` — this repo's canonical env-var registry ("Task Configuration Properties") and CLAUDE.md's "Key Configuration Variables" table are where operators actually look for env vars, not just the runbook. Add `HUB_SOURCE_ATTACK_LOCKOUT_ENABLED` to `CONFIGURATION.md` in the same style as neighboring entries.

## 6. Rollout (operator-driven, post-merge — not code changes)

- [ ] 6.1 Before enabling in any environment that has seen prior source-attack false positives, identify known senders (e.g. VHA) and pre-create their exception records via the admin API.
- [ ] 6.2 Enable `hub.source-attack-lockout.enabled=true` in dev/onboarding first; monitor deny-list additions for a period before promoting to higher environments.
