# Fleet-wide circuit breaker reset, with SQS observability

**Ticket:** IGDD-3599
**Repos:** `izgw-hub` (this change), `izg-configuration-console` (second PR, same ticket)

## Why

An operator cannot clear a circuit breaker across the Hub fleet. The Configuration
Console reports that the reset succeeded anyway.

Production runs four Hub tasks in two regions. Measured on 2026-10-22:

| Region | Hosts |
|---|---|
| us-east-1 | `ip-10-9-93-145`, `ip-10-9-92-73` |
| us-west-1 | `ip-10-161-102-113`, `ip-10-161-101-98` |

Circuit breaker state lives in a private in-memory `cache` map inside
`ElasticStatusRepository`. The gate that blocks traffic reads that same per-task map
(`izgw-core` `MessageSender.java:252`). Nothing writes the state to Elastic, DynamoDB,
or SQS.

### Defect 1 — the reset reaches one task of four

`POST /rest/reset` and `POST /rest/reset/{id}` clear breakers on the single task that
answers the request. Global Accelerator routes the call to one region. The ALB in that
region then picks one task. The other three tasks keep their breakers thrown.

The Admin Operations page shows the asymmetry. The Database Refresh card calls
`refresh?all=true`, which already reaches every host in every region. The Circuit
Breaker Reset card calls `/rest/reset`, which does not.

### Defect 2 — the documented workaround sends the wrong HTTP method

`GET /rest/refresh?all=true&reset=true` was the only path believed to reset breakers
fleet-wide. It resets nothing.

`callEndpoint` (`DbController.java:443`) never calls `setRequestMethod`.
`HttpURLConnection` defaults to **GET**. The target,
`StatusController.resetCircuitBreakers()`, is `@PostMapping("/reset")`. So the Hub
sends `GET /rest/reset` to an endpoint that accepts POST only.

`AccessControlValve` runs before Spring and rejects the request as an unregistered
path. The request never reaches the controller, so the `admin` role requirement is
never evaluated.

Confirmed in production on 2026-10-22. One `refresh?all=true&reset=true` under
`eventId 10009092073.703482` produced eight entries, paired on each of the four hosts:

```
ERROR gov.cdc.izgateway.security.AccessControlValve
      Access denied to unknown path /rest/reset address by
      prod.phiz-project.org at 10.9.93.145
      requestUri=/rest/reset  method=GET  commonName=prod.phiz-project.org

WARN  gov.cdc.izgateway.db.DbController
      Could not call ip-10-9-93-145.ec2.internal/rest/reset:
```

The client certificate is presented and accepted. `commonName` resolves to
`prod.phiz-project.org`, which is the Hub certificate. The transport layer is healthy.
A search for the catch-block phrase "Exception calling" over the same window returned
nothing, which also rules out a transport failure.

**An interim one-line fix exists.** Adding `con.setRequestMethod("POST")` to
`callEndpoint` makes the existing path work, without waiting for this change. Before
relying on it, confirm that `AccessControlRegistry` registers `/rest/reset` for POST —
the valve reported the path as unknown, and this change has not verified which methods
the registry records. This change deletes `callEndpoint` entirely, so the one-line fix
is a hotfix option rather than part of the plan.

### Defect 3 — a failed reset reports OK

Both call sites discard the result of `resetEndpoint()`:

```java
// RefreshQueueService.handleRefreshRequest — the SQS path
if (request.reset()) {
    dbController.resetEndpoint(SystemUtils.getHostName(), request.eventId());
}
RefreshResponse response = new RefreshResponse(..., "OK");   // hardcoded

// DbController.getRefreshed — the local path
results.put(REGION + ":" + me, "OK (Local)");   // written before the attempt
if (reset) { resetEndpoint(me, eventId); }
```

So the four failures above were reported to the caller as `OK`.

### Defect 4 — the status code is never logged

`callEndpoint` logs `"Could not call {}{}: {}"` with the error body as the third
argument. The body was empty, so the message ends at the colon. The status code exists
only in the discarded return value. The one fact needed to diagnose Defect 2 is the one
fact not recorded.

### Defect 5 — SQS traffic is close to invisible

`RefreshQueueService` holds 16 log statements. On a healthy run they produce almost
nothing:

| Event | Logged today |
|---|---|
| Request sent to another host | Nothing. Success is not logged. |
| Request received | DEBUG only, and `gov.cdc.izgateway` is INFO, so never emitted |
| Request handled | INFO, but the format string has no placeholder, so the payload is dropped |
| Response sent back | Nothing. Success is not logged. |
| Response received | DEBUG only, never emitted |
| A host times out | Nothing. The result appears only in the HTTP body. |

An operator cannot see who sent what, to which queue, or whether a reply returned.

## What changes

1. `POST /rest/reset` and `POST /rest/reset/{id}` send an SQS reset message to every
   host in every region, and wait for the replies.
2. Every SQS send and receive is logged at INFO, with the queue name and the direction.
   One summary line closes each operation.
3. A host that fails to reset is never reported as `OK`.
4. Both endpoints also return a per-region summary that carries no host name and no
   instance count, so a consumer can forward it to a browser unchanged.

## Capabilities

| Capability | Covers |
|---|---|
| `circuit-breaker-reset` | The observable contract of the two reset endpoints: scope, response shape, per-host outcome, the browser-safe per-region summary, and honest reporting of failure. |
| `cross-instance-messaging` | Carrying an administrative command to every Hub instance in every region, and recording each message so an operator can trace it. Serves the reset endpoints and `/rest/refresh`. |

## Decisions

These were settled before drafting. The design artifact carries the detail.

| Topic | Decision |
|---|---|
| Scope | The fan-out and the logging ship as one change. Both touch `RefreshQueueService`. |
| Recursion | SQS handlers call `endpointStatusService` directly. No handler calls `/rest/reset` over HTTP. |
| `/rest/reset/{id}` body | Keep every existing field. Add `hosts` and `regions`. Existing fields keep their names, but the Console still changes: it removes `hosts` before answering the browser. |
| `/rest/reset` body | New envelope, `{ destinations, hosts, regions }`. No caller reads the current body. |
| Partial failure | Return 200 with every host marked in `hosts` and every region marked in `regions`. The Console reads `regions`. |
| `refresh?reset=true` | Kept, and reimplemented to call the service directly. |
| Reply timeout | Stays hardcoded at 15 seconds. No new property, either side. |
| `jti` in logs | Full value, in a field named `keyId`, to match `ApiKeyAuditLogger`. |
| Queue lifecycle | Report `No Queue` separately from `Send Failure`. No reaper. |
| Testing | Unit tests with a mocked `SqsClient`. Dev region 2 is scaled by hand for verification. |
| Delivery | The Console PR ships first. |

## Alternatives rejected

**Point the Reset All button at `refresh?all=true&reset=true`.** The Database Refresh
card already calls `refresh?all=true`. The two buttons then do the same work, and
"reset" loses one meaning. Defect 2 also shows that this path never reset anything.

**Add `?all=true` to `/rest/reset`, defaulting to local.** It mirrors the refresh idiom
and stays backward compatible. Rejected because it keeps single-task behavior as the
default, which is the behavior nobody wants. Nothing in `testing/` or `src/test` calls
`/rest/reset`, so compatibility costs nothing to drop.

**Add a queue reaper.** Elastic reports hosts seen in the last three minutes
(`HostRepository.java:136`). A short logging gap can make a reaper delete the queue of a
live host and break its fan-out.

**Make the reply timeout a property.** The Console timeout must stay above the Hub wait,
and nothing enforces that relationship. A hardcoded pair cannot drift.

## Affected modules

- `gov.cdc.izgateway.db` — `RefreshQueueService`, `DbController`. The fan-out moves out
  of `RefreshQueueService`, which is built with `new` at `DbController:231` and is
  therefore unreachable from `StatusController`.
- `gov.cdc.izgateway.status` — `StatusController`.
- `izgw-core` and `izgw-bom` — no change. No shared type changes.

## FIPS compliance

No impact. This change adds no cryptographic operation and no keystore access. The SQS
client and the existing TLS context are unchanged.

## PHI exposure

No impact. SQS messages carry `reset`, `eventId`, `senderHost`, `senderRegion`, and
`jti`. None is PHI, and none passes through `PHIZ_MODE` masking.

`jti` is the sort key of the `ApiKeyCredential` record. It is an identifier, not a
secret. The Hub already writes it unmasked at INFO in `ApiKeyAuditLogger`, as `keyId`.
The new lines use the same field name, so one Elastic query returns the revocation, the
expiry, and every eviction message for a credential.

## Backward compatibility

- SOAP WSDL: untouched.
- `POST /rest/reset/{id}`: additive. Existing fields keep their names and values. Older
  callers ignore the new `hosts` and `regions` keys.
- `POST /rest/reset`: the body changes shape. `resetAllCircuitBreakers()` counts settled
  promises and never reads the body, and no test reads it.
- `GET /rest/refresh`: parameters and response shape unchanged.

## Performance

No impact on the routing path. Reset is an administrative operation and does not run
during message routing.

An administrative reset gains up to 15 seconds of latency while the Hub waits for SQS
replies. That matches the existing wait on `refresh?all=true`. Both Configuration
Console literals move from 5000 ms to 20000 ms to stay above it.

`izghubdbrefresh.ts:60` already sets 5000 ms against an endpoint that waits 15 seconds.
That button reports failure today whenever a host is slow. The same PR corrects it.

## Open items

None. `AccessControlRegistry` does register `/rest/reset`, for POST only.
`registerPathsAndRoles` reads `method` from the merged `RequestMapping`, and
`@PostMapping` carries `method = POST`. A GET matches no registered pair, which is why
the valve called the path unknown. This also confirms that the Configuration Console
reaches the endpoint today, so the single-instance reset works.
