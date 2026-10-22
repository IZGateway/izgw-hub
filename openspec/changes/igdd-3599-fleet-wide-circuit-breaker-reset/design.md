# Design

## Context

See `proposal.md` for motivation and for the five confirmed defects.

Three facts about the current code shape the approach.

`RefreshQueueService` is not a Spring bean. `DbController` builds it with `new` at
`DbController.java:231` and passes itself into the constructor. The service then calls
back into the controller. `StatusController` has no way to reach it.

`DbControllerConfiguration` is an autowired component, but its `refresh()` method is
private. Only `DbController` can call it.

Commands reach a remote instance as an SQS message whose body is the literal string
`RefreshRequest`. The payload travels in message attributes, not in the body.

## Goals / Non-Goals

**Goals:**

- One messaging component that both controllers can use.
- A received command applied in process, never by calling the local REST API.
- An outcome for every instance, carried back to the caller.
- A log trail that lets an operator follow one operation across every instance.
- Safe behavior while a rolling deployment runs mixed versions.

**Non-Goals:**

- Durable circuit breaker state. See `proposal.md` - Out of scope.
- Reaping orphaned queues.
- Changing how instances are discovered.
- Any Terraform change.
- Any new configuration property, on either side.

## Decisions

### D1 — Invert the dependency rather than expose the controller

`RefreshQueueService` becomes a Spring `@Service`. It depends on services, never on a
controller:

```
StatusController ──┐
                   ├──> InstanceMessagingService ──> EndpointStatusService
DbController ──────┘                             ──> DbControllerConfiguration
                                                 ──> ApiKeyPrincipalProvider
                                                 ──> IHostRepository
```

`DbControllerConfiguration.refresh()` changes from private to public so the messaging
bean can call it.

**Alternative rejected:** keep the callback and pass `DbController` in as an interface.
That leaves a controller on the dependency path of a background thread, and it keeps
the cycle that forced the `new` in the first place.

**Alternative rejected:** give `StatusController` its own copy of the service. Two
copies create two listener threads on the same queue, and the two compete for messages.

### D2 — One command message, not two

A single command record carries every administrative action:

| Field | Meaning |
|---|---|
| `refreshConfig` | Reload configuration from the datastore |
| `resetBreakers` | Clear circuit breakers |
| `destId` | Limit the reset to one destination. Null means all destinations |
| `eventId` | Correlation identifier |
| `senderHost`, `senderRegion` | Where to send the reply |
| `jti` | Credential to evict. Null means none |

**Alternative rejected:** a separate `ResetRequest` message type. It duplicates the
send loop, the reply queue handling, and the timeout logic for no behavioral gain.

### D3 — Keep the message wire-compatible during a rolling deployment

An ECS rolling deployment runs old and new instances together for minutes. Both
directions must stay safe.

The body string stays `RefreshRequest`. The existing attribute names stay unchanged:
`reset`, `eventId`, `senderHost`, `senderRegion`, `jti`. New attributes are added
alongside them.

| Direction | Behavior |
|---|---|
| New sends, old receives | The old instance reads the attributes it knows and ignores the rest. A reset arrives as a full reset rather than a per-destination one. |
| Old sends, new receives | `destId` is absent, so the new instance resets all destinations. `refreshConfig` is absent, so the new instance defaults it to true, matching old behavior. |

An old instance that receives a reset still applies it over HTTP and still fails
(Defect 2). Its reply still reads `OK`. That is the behavior of the release being
replaced, and it ends when the deployment completes.

### D4 — Internal application bypasses the access control valve, deliberately

Today a received command is applied by calling `/rest/reset` over HTTPS. That call
passes through `AccessControlValve` and then through `@RolesAllowed`. After this change
the handler calls `EndpointStatusService` in process, so neither check runs.

That is correct and intended. Authorization already happened once, on the instance that
received the operator's request. A second check on an internal hop guards nothing: the
message arrived on a queue that only Hub instances can write to, inside the account.

The Spring Security filter chain is unchanged. No filter is added, removed, or
reordered. `@RolesAllowed(Roles.ADMIN)` stays on both reset endpoints and still governs
the operator's request.

**Alternative rejected:** keep the HTTP hop and fix the method with
`con.setRequestMethod("POST")`. It preserves a defense that protects nothing, keeps a
network round trip per instance inside a loop, and leaves the silent-OK defect in place.

### D5 — Shape each response for its consumer

The two endpoints differ because their consumers differ.

```
POST /rest/reset                    POST /rest/reset/{id}
{                                   {
  "destinations": { ... },            "destId": "md",
  "hosts": {                          "status": "Connected",
    "us-east-1:ip-10-9-93-145":       "hosts": { ... }
        "OK (Local)",               }
    "us-west-1:ip-10-161-101-98":
        "Timed Out"
  }
}
```

`/rest/reset` gets an envelope. Its body is a map keyed by destination identifier, so
there is no free key for host outcomes, and nothing reads that body today.

`/rest/reset/{id}` gets an added member. The Configuration Console passes that body
through to the browser, so existing fields keep their names and meaning.

Host keys use `region:hostname`, matching the format `/rest/refresh` already returns.

**The host keys disclose internal topology.** They carry private AWS DNS names such as
`ip-10-9-93-145.ec2.internal`, which reveal internal addressing, the region layout and
the instance count. Both reset endpoints require `admin` and run over mutual TLS, so
the Hub treats its caller as a trusted peer and returns the names in full.

A consumer that forwards this body to an untrusted client MUST aggregate or remove the
host keys first. The Configuration Console is such a consumer, and it already applies
this rule elsewhere: `[...slug].ts` deletes `statusBy` before responding, and
`fetchEndpointStatus.ts` performs the same redaction on initial load. How the Console
presents the outcomes is specified in its own change, not here.

### D6 — Keep the reply wait hardcoded at 15 seconds

A property would let an operator raise the Hub wait above the Configuration Console
timeout. Nothing enforces that relationship, and breaking it makes the console report
failures for resets that succeed. A hardcoded pair cannot drift. See `proposal.md` -
Alternatives rejected.

### D7 — Structured log fields

Every message record uses `Markers2.append` with this field set:

| Field | Values |
|---|---|
| `eventType` | `SQS_SEND`, `SQS_RECEIVE`, `SQS_OPERATION_COMPLETE` |
| `direction` | `send`, `receive` |
| `queueName` | The SQS queue name |
| `targetHost`, `targetRegion` | Present on a send |
| `messageType` | `RefreshRequest`, `RefreshResponse` |
| `keyId` | The credential identifier, when the command carries one |
| `result` | `OK`, `Timed Out`, `No Queue`, `Send Failure` |

`eventId` is already in the MDC and needs no marker.

`keyId` matches `ApiKeyAuditLogger`, so one query returns the audit records and the
messaging records for a credential together. The value is not masked, which matches the
existing treatment. See `proposal.md` - PHI exposure.

Marker names stay suppressed. `logback-spring.xml` sets `includeTags=false` because
marker names collide with the Filebeat environment tag (IGDD-3562). Appended fields are
unaffected.

### D8 — Error mapping

The reset endpoints are REST. They raise no SOAP fault. The circuit breaker they clear
does govern the SOAP path.

| Condition | Fault | HTTP | Retry strategy |
|---|---|---|---|
| Destination not found on reset | none (REST) | 404 | n/a |
| Caller lacks `admin` | none (REST) | 403 | n/a |
| One instance times out | none | 200, outcome `Timed Out` | n/a |
| Queue missing for an instance | none | 200, outcome `No Queue` | n/a |
| Breaker thrown, message routed | `DestinationConnectionFault` code 18 (`CircuitBreakerThrown`) | per SOAP mapping | `CHECK_IIS_STATUS` |

No new destination type is introduced, so circuit breaker thresholds and the retry
classification are unchanged.

### D9 — No DynamoDB and no FIPS impact

No new entity and no new table access pattern. The messaging path touches SQS, the
in-memory status cache, and Elasticsearch for host discovery.

No BouncyCastle API is called. The outbound HTTPS call that used the default SSL
context is deleted, which removes a user of that context rather than adding one. TLS
configuration and the BCFIPS provider are untouched.

## Flow

```
Operator                Instance A (us-east-1)            Instance B..D
   |                           |                                |
   |-- POST /rest/reset ------>|                                |
   |                           | AccessControlValve + admin     |
   |                           | role checked once, here        |
   |                           |                                |
   |                           | reset breakers in process      |
   |                           | log SQS_SEND (per instance) -->|
   |                           |                                | log SQS_RECEIVE
   |                           |                                | reset in process
   |                           |                                | (no HTTP, no valve)
   |                           |<-- reply, log SQS_RECEIVE -----| log SQS_SEND
   |                           |                                |
   |                           | all replied, or 15s elapsed    |
   |                           | log SQS_OPERATION_COMPLETE     |
   |<-- 200 + hosts map -------|                                |
```

## Risks / Trade-offs

**A listener thread starts twice if both controllers construct the service.** → The
service is a singleton bean. Queue creation and the listener start move into
`@PostConstruct`, so Spring guarantees one instance.

**An operator waits up to 15 seconds for a reset.** → Replies normally arrive in well
under one second, because `awaitRefreshResponses` returns as soon as every instance
answers. The 15 seconds is a ceiling, reached only when an instance is silent.

**A rolling deployment leaves old instances that still fail the reset.** → The failure
is the behavior of the release being replaced, not a regression. It ends when the
deployment completes. Both PRs deploy together.

**The host list can name a retired instance for up to three minutes.** → The outcome
reports `No Queue` rather than success, so the operator sees it and can tell it apart
from a real fault. See the `cross-instance-messaging` spec.

**A newly started instance that has logged nothing is missed silently.** → Accepted.
Fixing it means a second source of truth for instance discovery, which is a larger
change. The specs record the limitation.

**Making `DbControllerConfiguration.refresh()` public widens its reach.** → The method
reloads five caches from the datastore and holds no secret. The widening is from one
caller to two, both inside this module.

## Migration Plan

1. Deploy the Configuration Console change and the Hub change in the same release.
2. During the ECS rolling deployment, mixed versions run together. D3 covers both
   message directions.
3. Verify on dev with region 2 scaled to one task by hand. Post to `/rest/reset` and
   confirm two entries in the `hosts` map.
4. Verify on production by posting to `/rest/reset` and confirming four entries, two
   per region.

**Rollback:** revert both deployments together. The message stays wire-compatible in
both directions, so a reverted instance reads messages from a not-yet-reverted one. No
queue, schema, or stored data changes, so nothing needs undoing.

## Resolved Questions

**Does `AccessControlRegistry` register `/rest/reset` for POST?** Yes.
`registerPathsAndRoles` reads `method` from the merged `RequestMapping` annotation, and
`@PostMapping` carries `method = POST`. The registry therefore holds `/rest/reset` for
POST and for no other method.

This explains Defect 2 exactly. A GET to that path matches no registered pair, so the
valve rejects it as an unknown path. It also confirms that the Configuration Console
reaches the endpoint today with its POST, so the single-instance reset does work.

No open questions remain.
