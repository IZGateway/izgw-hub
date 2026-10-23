# Spec Delta

## Purpose

Carries an administrative command from the Hub instance that receives a request to
every other Hub instance in every region, and records each message sent and received so
that an operator can trace one operation end to end.

## ADDED Requirements

### Requirement: Deliver a command to every known instance

The gateway SHALL send an administrative command to every Hub instance it knows about,
in the region of that instance.

#### Scenario: Instances in two regions

- **GIVEN** the host list holds two instances in one region and two in another
- **WHEN** the gateway distributes a command
- **THEN** each of the three remote instances receives one message
- **AND** the originating instance applies the command locally without a message

#### Scenario: No other instances exist

- **GIVEN** the originating instance is the only one in the host list
- **WHEN** the gateway distributes a command
- **THEN** no message is sent
- **AND** the gateway returns without waiting

### Requirement: Apply commands locally, never through the HTTP API

An instance that receives a command SHALL apply it through an internal call. An
instance SHALL NOT apply a command by calling its own REST API.

#### Scenario: A received command does not cascade

- **GIVEN** an instance receives a reset command
- **WHEN** that instance applies the command
- **THEN** no further message is sent to any instance
- **AND** the operation terminates after one round of distribution

#### Scenario: A combined refresh and reset distributes once

- **GIVEN** an administrator requests a refresh with a reset across all instances
- **WHEN** the gateway distributes the command
- **THEN** each remote instance receives exactly one message
- **AND** no instance sends a message as a result of applying it

### Requirement: Wait for replies within a bounded period

The gateway SHALL wait for a reply from every instance it messaged, and SHALL stop
waiting after 15 seconds.

#### Scenario: All replies arrive early

- **GIVEN** every messaged instance replies in under one second
- **WHEN** the last reply arrives
- **THEN** the gateway returns at once
- **AND** the gateway does not wait the full period

#### Scenario: One instance stays silent

- **GIVEN** one messaged instance never replies
- **WHEN** 15 seconds elapse
- **THEN** the gateway stops waiting
- **AND** the outcome for that instance reports a timeout
- **AND** the outcomes for the replying instances are preserved

### Requirement: Record every message sent and received

The gateway SHALL write a log record at INFO level for each message it sends and each
message it receives. Each record SHALL carry the queue name, the direction, the message
type, and the correlation identifier.

#### Scenario: A send is recorded

- **GIVEN** the gateway sends a command to a remote instance
- **WHEN** the send succeeds
- **THEN** a record is written at INFO level
- **AND** the record carries the queue name, the target region, and the correlation
  identifier

#### Scenario: A receive is recorded

- **GIVEN** an instance receives a command
- **WHEN** the instance reads the message
- **THEN** a record is written at INFO level
- **AND** the record carries the queue name and the correlation identifier

#### Scenario: A reply is recorded on both sides

- **GIVEN** a remote instance finishes applying a command
- **WHEN** it sends its reply
- **THEN** the sending instance records the reply at INFO level
- **AND** the originating instance records the reply when it reads it

### Requirement: Record the application of a command

Every instance SHALL write a record at INFO level when it applies a command, whether
the command arrived over a message or was given to it directly. The record SHALL name
the outcome.

#### Scenario: The instance that receives the operator's request

- **GIVEN** an operator sends a reset to one instance
- **WHEN** that instance applies the command to itself
- **THEN** a record is written at INFO level
- **AND** the record names the outcome of the application

#### Scenario: An instance that receives a message

- **GIVEN** an instance reads a command from its queue
- **WHEN** that instance applies the command
- **THEN** a record is written at INFO level
- **AND** the record carries the correlation identifier of the operation

#### Scenario: The application fails

- **GIVEN** applying a command raises an error
- **WHEN** the record is written
- **THEN** the record names the failure
- **AND** the record does not report success

### Requirement: Close each operation with a summary record

The gateway SHALL write one summary record at INFO level when a distributed operation
ends, naming the count of instances reached and the count that failed.

#### Scenario: An operator checks one operation

- **GIVEN** a distributed operation reached four instances and one timed out
- **WHEN** the operation ends
- **THEN** one summary record is written
- **AND** the record reports four instances and one failure
- **AND** the record carries the correlation identifier of the operation

### Requirement: Name the credential identifier consistently

When a command carries an API key identifier, the gateway SHALL record that identifier
in a field named `keyId`, matching the field that API key audit records already use.

#### Scenario: A credential eviction is traced

- **GIVEN** a command carries an API key identifier
- **WHEN** the gateway records the send and the receive
- **THEN** both records carry the identifier in the `keyId` field
- **AND** one query on that field returns the audit records and these records together

### Requirement: Report a missing queue distinctly from a send failure

The gateway SHALL distinguish an instance whose queue no longer exists from an instance
whose message could not be sent for another reason.

#### Scenario: A retired instance is still listed

- **GIVEN** an instance appears in the host list but its queue was deleted
- **WHEN** the gateway attempts to send to it
- **THEN** the outcome reports a missing queue
- **AND** a warning record names the queue and the reason

### Requirement: Discover instances from recent activity

Instance discovery SHALL use instances that reported activity within the last three
minutes. The gateway SHALL NOT claim to reach instances outside that window.

#### Scenario: An instance was replaced recently

- **GIVEN** an instance stopped two minutes ago during a rolling deployment
- **WHEN** the gateway distributes a command
- **THEN** that instance is still in the host list
- **AND** the outcome for it reports a missing queue rather than success

#### Scenario: An instance started but has not reported activity

- **GIVEN** an instance started and has written no record yet
- **WHEN** the gateway distributes a command
- **THEN** that instance receives no message
- **AND** the response does not list it
