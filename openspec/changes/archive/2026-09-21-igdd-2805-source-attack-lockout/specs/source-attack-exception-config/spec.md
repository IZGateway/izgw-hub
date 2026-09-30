## ADDED Requirements

### Requirement: Sender-scoped source-attack exception record
Hub SHALL support persisting a source-attack exception record identifying a single sender (by
authenticated common name) as exempt from source-attack auto-lockout (see the `access-control`
capability). Each record SHALL be scoped to the current environment and SHALL include, at minimum:

- `sender` — the sender's common name (required, unique per environment)
- `reason` — free-text operator justification (required)
- `createdBy` / `createdOn` — audit fields identifying who created the exception and when

An exception record SHALL NOT include a destination/receiver scope. Per-receiver exceptions are not
supported by this capability — the requesting destination is not reliably known at the point Hub
evaluates a source-attack fault (see `design.md`), so a receiver-scoped field could not be honored and
is deliberately not offered, to avoid an operator configuring a restriction that appears to apply but
does not.

#### Scenario: Valid exception record
- **GIVEN** an admin submits `{ "sender": "VHA.example.gov", "reason": "Known false positive: patient name contains 'javascript'" }`
- **WHEN** the record is created
- **THEN** Hub persists a `SourceAttackExceptionRecord` for `VHA.example.gov` in the current environment, with `createdBy` set to the admin's identity and `createdOn` set to the current time

#### Scenario: Missing required field is rejected
- **GIVEN** an admin submits a record with `sender` omitted or blank
- **WHEN** the record is submitted for creation
- **THEN** Hub rejects the request and does not persist a record

#### Scenario: Duplicate sender replaces the existing exception
- **GIVEN** an exception already exists for `VHA.example.gov` in the current environment
- **WHEN** an admin creates another exception record for `VHA.example.gov` with a different `reason`
- **THEN** the existing record is replaced (same primary key: environment + sender), not duplicated

### Requirement: Admin-only exception administration
Hub SHALL expose REST endpoints to create, list, and delete source-attack exception records, restricted
to callers holding the `ADMIN` role (or the localhost/administrator bypass already used elsewhere in
Hub). Callers without the `ADMIN` role SHALL be denied access to these endpoints.

#### Scenario: Admin creates an exception
- **GIVEN** an authenticated caller with the `ADMIN` role
- **WHEN** the caller submits a valid exception record for a sender
- **THEN** the record is persisted and is returned by the list endpoint

#### Scenario: Admin removes an exception
- **GIVEN** an exception exists for `VHA.example.gov`
- **WHEN** an admin deletes the exception for `VHA.example.gov`
- **THEN** the record is removed, and subsequent source-attack detections for `VHA.example.gov` are no longer exempt from auto-lockout (once the change has propagated to the deny-list decision point)

#### Scenario: Non-admin caller is denied
- **GIVEN** an authenticated caller without the `ADMIN` role
- **WHEN** the caller attempts to create, list, or delete a source-attack exception record
- **THEN** Hub denies the request and does not perform the requested change

### Requirement: An exception suppresses lockout only, not detection or message rejection
A configured exception SHALL prevent the sender-wide deny-list add described in the `access-control`
capability. It SHALL NOT change `SoapMessageReader`'s pattern-scanning behavior, and SHALL NOT cause a
message that matched an attack pattern to be delivered. The triggering request SHALL still be rejected
with `SecurityFault.sourceAttack` (fault code `61`, retry strategy `CONTACT_SUPPORT`), exactly as it
would be without an exception configured.

#### Scenario: Exempted sender's flagged message is still rejected
- **GIVEN** `VHA.example.gov` has a configured source-attack exception
- **AND** `hub.source-attack-lockout.enabled` is `true`
- **WHEN** `VHA.example.gov` submits a message whose patient name contains "javascript"
- **THEN** the request is rejected with `SecurityFault.sourceAttack` (code `61`), the same as it would be for any other sender
- **AND** `VHA.example.gov` is NOT added to the deny list
- **AND** `VHA.example.gov` can resend the same request over a new connection without being blocked by the deny list
