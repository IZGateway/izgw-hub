## MODIFIED Requirements

### Requirement: Auto-lockout on detected source attack
When Hub catches a `SecurityFault` (`gov.cdc.izgateway.soap.fault.SecurityFault`) with fault code
`"61"` (Source Attack Exception) while processing an inbound SOAP request, and the auto-lockout feature
is enabled, Hub SHALL add the sending client's identity to the DynamoDB deny list via
`AccessControlService.addUserToDenyList`, unless the sender has a configured exception (see the
`source-attack-exception-config` capability). This SHALL happen in addition to, not instead of,
returning the `SecurityFault` (retry strategy `CONTACT_SUPPORT`) to the sender for the triggering
request — the deny-list add SHALL NOT change the fault, HTTP status, or response body already returned
for that request.

#### Scenario: Attack detected, lockout enabled, no exception configured
- **GIVEN** `hub.source-attack-lockout.enabled` is `true`
- **AND** the sending client `VHA.example.gov` has no source-attack exception configured
- **WHEN** an inbound SOAP request from `VHA.example.gov` triggers `SecurityFault.sourceAttack` (code `61`)
- **THEN** `VHA.example.gov` is added to the DynamoDB deny list with the fault's diagnostic detail as the reason
- **AND** the caller still receives the `SecurityFault` response for that request, unchanged

#### Scenario: Attack detected, lockout enabled, sender has an exception
- **GIVEN** `hub.source-attack-lockout.enabled` is `true`
- **AND** the sending client `VHA.example.gov` has a configured source-attack exception
- **WHEN** an inbound SOAP request from `VHA.example.gov` triggers `SecurityFault.sourceAttack` (code `61`)
- **THEN** `VHA.example.gov` is NOT added to the DynamoDB deny list
- **AND** the caller still receives the `SecurityFault` response for that request, unchanged
- **AND** Hub logs that the sender's exception suppressed the deny-list add

#### Scenario: Subsequent requests from a deny-listed sender are rejected
- **GIVEN** a sender was added to the deny list per the first scenario
- **WHEN** that sender makes a subsequent request to any destination
- **THEN** the request is rejected before reaching message processing, per existing deny-list enforcement (`AccessControlValve` → `checkAccess` → `isUserDenied`)

### Requirement: Auto-lockout master switch, default disabled
Hub SHALL gate the auto-lockout behavior described above behind a configuration property,
`hub.source-attack-lockout.enabled`, which SHALL default to `false` when unset. This property SHALL be
distinct from `security.enable-blacklist` and `security.blacklist.disabled` — those SHALL continue to
govern deny-list *enforcement* in general (i.e. whether an already-deny-listed sender is rejected) and
SHALL NOT be affected by, or affect, `hub.source-attack-lockout.enabled`.

#### Scenario: Lockout disabled (default)
- **GIVEN** `hub.source-attack-lockout.enabled` is unset (or explicitly `false`)
- **WHEN** an inbound SOAP request triggers `SecurityFault.sourceAttack` (code `61`)
- **THEN** the sender is NOT added to the deny list
- **AND** the caller still receives the `SecurityFault` response for that request, unchanged
- **AND** Hub logs that the sender was not deny-listed because auto-lockout is disabled

#### Scenario: Enabling the flag does not require redeploying application code
- **GIVEN** Hub is already running with `hub.source-attack-lockout.enabled=false`
- **WHEN** an operator changes the environment configuration to `hub.source-attack-lockout.enabled=true` and restarts/redeploys only configuration (no code change)
- **THEN** subsequent source-attack detections deny-list the sender per the first requirement

### Requirement: Source-attack fault discrimination by code
Hub SHALL trigger auto-lockout only for `SecurityFault` instances whose `getCode()` equals `"61"`.
`SecurityFault` instances with any other code (e.g. `"60"` general security, `"62"` user blacklisted,
`"63"` decryption failure, `"64"` access denied) SHALL NOT trigger an auto-lockout deny-list add, even
though some of them (`"62"`) also carry a non-null `endpoint`.

#### Scenario: User-blacklisted fault does not re-trigger auto-lockout
- **GIVEN** `hub.source-attack-lockout.enabled` is `true`
- **WHEN** a request from an already-deny-listed sender produces `SecurityFault.userBlacklisted` (code `62`)
- **THEN** Hub does not perform an additional deny-list add for that request

#### Scenario: General security fault does not trigger auto-lockout
- **GIVEN** `hub.source-attack-lockout.enabled` is `true`
- **WHEN** a request produces `SecurityFault.generalSecurity` (code `60`) — e.g. a source/destination access-control denial
- **THEN** Hub does not add the sender to the deny list as a result of that fault

### Requirement: Outbound (destination-response) source attacks SHALL NOT deny-list the original sender
A `SecurityFault.sourceAttack` (code `61`) can also occur while Hub scans a *destination IIS's response*
for attack patterns (`MessageSender`'s outbound message scan), which reaches the same fault-handling
path as an inbound detection. In that case the fault's `endpoint` is non-null (it carries the
destination, not `null`). Hub SHALL trigger the auto-lockout deny-list add described above only when
`getEndpoint()` is `null` (the inbound case). Hub SHALL NOT deny-list the original requesting sender as
a result of a source-attack fault whose `endpoint` is non-null.

#### Scenario: Malicious destination response does not lock out the requesting sender
- **GIVEN** `hub.source-attack-lockout.enabled` is `true`
- **AND** sender `GoodSender.example.gov` submits a valid request to destination `az`
- **WHEN** destination `az`'s response contains content that triggers `SecurityFault.sourceAttack` (code `61`, non-null `endpoint`)
- **THEN** `GoodSender.example.gov` is NOT added to the deny list as a result of this fault

### Requirement: Sender identity used for deny-listing
Hub SHALL identify the sender to deny-list using the authenticated request identity
(`RequestContext.getSourceInfo().getCommonName()`) — the same identity used elsewhere for deny-list
membership checks (`AccessControlService.isUserDenied`) — and SHALL NOT rely on
`SecurityFault.getEndpoint()`, which is `null` for inbound requests.

#### Scenario: Fault carries no endpoint information
- **GIVEN** an inbound request whose `SecurityFault.sourceAttack` fault has a `null` `endpoint` (the normal case for inbound requests)
- **WHEN** Hub processes the fault for auto-lockout
- **THEN** the sender is still correctly identified, using the request's authenticated common name, and deny-listed
