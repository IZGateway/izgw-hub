# Spec Delta

## Purpose

Lets an operator clear thrown circuit breakers across every Hub instance in every
region, so that traffic to a recovered destination resumes without a service restart,
and reports honestly which instances were cleared.

## ADDED Requirements

### Requirement: Reset all destinations across every instance

`POST /rest/reset` SHALL clear the circuit breaker for every destination on every Hub
instance in every region, not only on the instance that receives the request.

#### Scenario: Two regions, four instances

- **GIVEN** four Hub instances run across two regions
- **AND** a circuit breaker is thrown for destination `md` on all four
- **WHEN** an administrator posts to `/rest/reset`
- **THEN** the circuit breaker for `md` is cleared on all four instances
- **AND** the response reports an outcome for each of the four

#### Scenario: Traffic resumes after the reset

- **GIVEN** a thrown circuit breaker causes `DestinationConnectionFault` code 18
  (`CircuitBreakerThrown`, retry strategy `CHECK_IIS_STATUS`)
- **WHEN** an administrator posts to `/rest/reset`
- **AND** a message is then routed to that destination through any instance
- **THEN** the gateway does not raise `CircuitBreakerThrown` for that message

### Requirement: Reset one destination across every instance

`POST /rest/reset/{id}` SHALL clear the circuit breaker for the named destination on
every Hub instance in every region.

#### Scenario: Single destination, all instances

- **GIVEN** a circuit breaker is thrown for destination `md` on every instance
- **WHEN** an administrator posts to `/rest/reset/md`
- **THEN** the circuit breaker for `md` is cleared on every instance
- **AND** circuit breakers for other destinations are unchanged

#### Scenario: Unknown destination

- **GIVEN** no destination with identifier `nosuchdest` exists
- **WHEN** an administrator posts to `/rest/reset/nosuchdest`
- **THEN** the gateway responds 404
- **AND** no message is sent to any other instance

#### Scenario: Empty destination identifier

- **WHEN** an administrator posts to `/rest/reset/` with no identifier
- **THEN** the gateway responds 404
- **AND** no circuit breaker is cleared

### Requirement: Report an outcome for every instance

Both reset endpoints SHALL return an outcome for each known Hub instance, keyed by
region and host name.

#### Scenario: All instances succeed

- **WHEN** every instance clears its circuit breakers
- **THEN** the response carries one entry per instance
- **AND** each entry reports success

#### Scenario: One region does not answer

- **GIVEN** one instance does not reply within the wait period
- **WHEN** the reset completes on the others
- **THEN** the response status is 200
- **AND** the entry for the silent instance reports a timeout
- **AND** the entries for the other instances report success

#### Scenario: An instance has no message queue

- **GIVEN** an instance appears in the host list but its queue no longer exists
- **WHEN** the gateway attempts to reach it
- **THEN** the entry for that instance reports a missing queue
- **AND** that outcome is distinguishable from a send failure

### Requirement: Summarise the outcome by region

Both reset endpoints SHALL return a summary that gives one status per region. The
status SHALL be `complete` when every instance in that region reset, `partial` when
some did, and `failed` when none did. The summary SHALL carry one further entry,
`fleet`, when the list of instances could not be read.

#### Scenario: One region succeeds and one is degraded

- **GIVEN** every instance in one region resets
- **AND** one instance in a second region does not answer
- **WHEN** the response is built
- **THEN** the first region reports `complete`
- **AND** the second region reports `partial`

#### Scenario: A region is unreachable

- **GIVEN** no instance in a region resets
- **WHEN** the response is built
- **THEN** that region reports `failed`
- **AND** the status for every other region is unaffected

#### Scenario: The list of instances cannot be read

- **GIVEN** the instance that receives the request reset its own breakers
- **AND** the list of instances cannot be read
- **WHEN** the response is built
- **THEN** the summary carries a `fleet` entry that reports `failed`
- **AND** a consumer that reads only the summary sees that the operation is incomplete

### Requirement: Keep the region summary free of topology

The region summary SHALL carry no host name and no instance count, so that a consumer
can pass it to an untrusted client without redacting it.

#### Scenario: A consumer forwards the summary unchanged

- **GIVEN** a consumer reads only the region summary
- **WHEN** the consumer forwards it to a browser
- **THEN** no host name reaches the browser
- **AND** no count of instances reaches the browser

#### Scenario: Detail stays available to an authorised caller

- **GIVEN** a caller holds the `admin` role and reads the response directly
- **WHEN** the caller needs to know which instance failed
- **THEN** the per-instance outcomes remain in the response alongside the summary

### Requirement: Never report a failed reset as success

The gateway SHALL NOT report an instance as successful unless that instance confirmed
that it cleared its circuit breakers.

#### Scenario: The local reset fails

- **GIVEN** the instance that receives the request cannot clear its own breakers
- **WHEN** the response is built
- **THEN** the entry for that instance reports the failure
- **AND** the entry does not report success

#### Scenario: A remote instance fails

- **GIVEN** a remote instance receives the command and cannot apply it
- **WHEN** that instance replies
- **THEN** the reply carries the failure
- **AND** the response entry for that instance reports the failure

#### Scenario: The status cannot be read

- **GIVEN** an instance cannot read destination status from Elasticsearch
- **WHEN** it applies a reset of every destination
- **THEN** the outcome for that instance names the failed status read
- **AND** the outcome does not report success
- **AND** the outcome is distinguishable from a successful read that holds no status

#### Scenario: An instance holds no status at all

- **GIVEN** an instance reads status successfully and holds none
- **WHEN** it applies a reset of every destination
- **THEN** the outcome reports success
- **AND** no circuit breaker is reported as cleared

#### Scenario: A remote instance holds no status for the destination

- **GIVEN** a remote instance receives a reset for one destination
- **AND** that instance holds no status for the destination
- **WHEN** that instance replies
- **THEN** the reply names the destination and does not report success
- **AND** the region of that instance does not report `complete`

#### Scenario: The fleet was never reached

- **GIVEN** the list of instances cannot be read
- **WHEN** the response is built
- **THEN** the response does not report the operation as complete
- **AND** the entry for the instance that reset locally keeps its own outcome

### Requirement: Preserve the existing response fields for a single destination

`POST /rest/reset/{id}` SHALL keep every field of the destination status it returns
today, and add the per-instance outcomes as a separate member.

#### Scenario: An existing consumer reads the status

- **GIVEN** a consumer reads `destId` and `status` from the response body
- **WHEN** the consumer posts to `/rest/reset/md` after this change
- **THEN** `destId` and `status` are present with unchanged meaning
- **AND** the consumer can ignore the per-instance outcomes without error

### Requirement: Restrict reset to administrators

Both reset endpoints SHALL require the `admin` role.

#### Scenario: A caller without the admin role

- **GIVEN** a client certificate that does not carry the `admin` role
- **WHEN** that client posts to `/rest/reset`
- **THEN** the gateway refuses the request
- **AND** no circuit breaker is cleared on any instance

### Requirement: Reset does not survive a status refresh

A reset SHALL affect only the current in-memory state. The gateway SHALL NOT present a
reset as durable.

#### Scenario: The destination is still failing

- **GIVEN** the recorded transaction history still shows repeated failures and no newer
  success for a destination
- **WHEN** an administrator resets the circuit breaker
- **AND** the scheduled status refresh next runs
- **THEN** the circuit breaker is thrown again on each instance
