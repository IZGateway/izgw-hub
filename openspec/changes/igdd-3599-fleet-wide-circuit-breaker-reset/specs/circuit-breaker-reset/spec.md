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
