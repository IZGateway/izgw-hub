# Circuit breaker reset across instances

This document explains how a circuit breaker reset reaches every Hub instance, and how
to trace one reset in Elasticsearch.

Ticket: IGDD-3599.

## What a reset does

A circuit breaker lives in memory on one Hub task. The gate that blocks outbound traffic
reads that per-task value. Nothing writes it to Elasticsearch or DynamoDB.

A reset therefore has to reach every task. The Hub does that with SQS. The instance that
receives the request clears its own breakers, sends a message to every other instance,
and waits up to 15 seconds for the replies.

## Endpoints

Both endpoints need the `admin` role.

| Endpoint | Scope |
|---|---|
| `POST /rest/reset` | Every destination, on every instance |
| `POST /rest/reset/{id}` | One destination, on every instance |

Both answer **200 even when an instance fails**. Read the body, not the status code.

```json
{
  "destinations": { "md": { "destId": "md", "status": "Connected" } },
  "hosts": {
    "us-east-1:ip-10-9-93-145.ec2.internal": "OK (Local)",
    "us-west-1:ip-10-161-101-98.us-west-1.compute.internal": "Timed Out"
  },
  "regions": { "us-east-1": "complete", "us-west-1": "partial" }
}
```

`hosts` values: `OK (Local)`, `OK`, `Timed Out`, `No Queue`, `Send Failure`,
`Internal Error`, `Discovery Unavailable`.

- `Internal Error` means this instance could not read its own reply queue, so it never
  learned what the others did. Every reply collected before the failure keeps its
  outcome.
- A reset of one destination reports `No status for destination <id>` when an instance
  holds no status for it. The breaker state on that instance is unknown, so the outcome
  is not `OK`.
- Either reset reports `Could not read status from Elasticsearch` when an instance
  cannot fill its status cache, and `Could not parse the status response: <detail>` when
  Elasticsearch answers 200 with a body the status query does not recognise. An instance
  that reads successfully and holds no status reports `OK`, because it holds no thrown
  breaker to clear.

`hosts` also carries the key `fleet`, value `Discovery Unavailable`, when the list of
instances could not be read from Elasticsearch. Nothing was sent in that case. The local
reset still stands, and the rest of the fleet is unknown. The key is not a host name, so
it is safe to forward.

`regions` values: `complete` when every instance in that region reset, `partial` when
some did, `failed` when none did. The map carries one further key, `fleet`, with the
value `failed`, when the list of instances could not be read. A consumer that renders
only `regions` therefore still shows the failure.

CAUTION: Do not forward `hosts` to an untrusted client. The keys carry private AWS DNS
names, which disclose internal addressing and the instance count. Forward `regions`
instead. It carries no host name and no count.

CAUTION: Do not forward `destinations` to an untrusted client either. Each destination
carries a `statusBy` field holding the private DNS name of the instance that last
checked it. The Configuration Console already removes that field on the
per-destination route.

The `regions` vocabulary is closed at those three words. A fourth word is a coordinated
change with `izg-configuration-console`, because every deployed Console renders an
unrecognised value as a warning. The `fleet` key keeps that rule: its value is `failed`,
one of the three words. The Console renders it as one more row, labelled `fleet`.

## Tracing one reset in Elasticsearch

Every message produces a record at INFO. One operation carries one `eventId` across
every instance.

### Start from the summary

```lucene
eventType:"SQS_OPERATION_COMPLETE"
```

One record per operation, written by the instance that received the request. It names
the instance count, the success count, the failure count, and the per-region status.
This answers "did every region apply the command" without a search across hosts.

The three counts describe instances only. A discovery failure is not an instance, so it
does not raise the failure count. Read `regions` in the same record for it: a `fleet`
entry means that the list of instances could not be read.

### Follow one operation end to end

```lucene
eventId:"<the eventId from the summary>"
```

Sort ascending. A healthy two-instance operation produces four records:

| Order | Instance | Record |
|---|---|---|
| 1 | sender | `SQS_SEND`, `messageType: RefreshRequest` |
| 2 | receiver | `SQS_RECEIVE`, `messageType: RefreshRequest` |
| 3 | receiver | `SQS_SEND`, `messageType: RefreshResponse` |
| 4 | sender | `SQS_RECEIVE`, `messageType: RefreshResponse` |

A missing record tells you where the operation stopped.

### Fields

| Field | Meaning |
|---|---|
| `eventType` | `SQS_SEND`, `SQS_RECEIVE`, `SQS_SEND_FAILED`, `SQS_RECEIVE_FAILED`, `COMMAND_APPLIED`, `SQS_OPERATION_COMPLETE`, `FLEET_DISCOVERY_FAILED` |
| `direction` | `send` or `receive` |
| `queueName` | The SQS queue, named after the host |
| `messageType` | `RefreshRequest` or `RefreshResponse` |
| `targetHost`, `targetRegion` | Present on a send |
| `replyEventId` | On a received reply only: the operation that reply answers. It differs from `eventId` when a late reply arrives from an operation that already timed out |
| `result` | The outcome for that instance |
| `keyId` | The API key identifier, when the command evicts a credential |

`keyId` matches the field `ApiKeyAuditLogger` already writes, so one query returns the
revocation, the expiry and every eviction message for a credential:

```lucene
keyId:"<the credential identifier>"
```

### Find a failed send

```lucene
eventType:"SQS_SEND_FAILED"
```

Read `result` to tell the two causes apart:

- `No Queue` means the queue does not exist. Usually a task that stopped recently.
  Instance discovery reads Elasticsearch over the last three minutes, so a replaced task
  stays in the list for up to three minutes after its queue is deleted. This is not a
  fault.
- `Send Failure` means SQS refused the message for another reason. Investigate.

## Known limits

**A reset is not durable.** Every instance recomputes status from Elasticsearch every
5 minutes. If the recorded history still shows three or more failures with no newer
success, the breaker returns within 5 minutes. Clearing a breaker for a destination that
is still failing buys one attempt, not a permanent change.

**Discovery reaches instances that logged recently, not instances that are running.** An
instance that started but has written no record yet receives no message, and does not
appear in the response.

**Two operations on one instance share one reply queue.** A reset and a refresh that run
at the same time on the same instance read the same queue. Each one releases a reply that
answers the other, so the other can still read it. A reply therefore reaches the right
operation, but it can reach it a second or two late. A reply whose operation already
ended stays on the queue until SQS retention expires, and each later operation reads and
releases it about once a second. Run one fleet-wide operation at a time where you can.

**A failed host list stops the operation.** The gateway sends nothing when it cannot read
the list of instances, and reports `fleet: Discovery Unavailable`. Search for
`eventType:"FLEET_DISCOVERY_FAILED"` to find the Elasticsearch failure behind it.

**Replies are bounded at 15 seconds.** An instance that does not answer in that time
reads `Timed Out`. The reset may still have applied on it. The timeout bounds the wait,
not the work.

## Queues

Each instance creates two queues at startup and deletes them through a shutdown hook:

```
izgw_<hostname with dots replaced by dashes>_refresh-requests
izgw_<hostname with dots replaced by dashes>_refresh-responses
```

A hard kill skips the shutdown hook and leaves the queues behind. Nothing reaps them
today. An orphaned queue causes no error, because discovery reads Elasticsearch rather
than SQS.
