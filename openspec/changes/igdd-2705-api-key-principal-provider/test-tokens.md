# Test JWT for Local Development

**FOR LOCAL DEVELOPMENT ONLY — DO NOT USE IN DEPLOYED ENVIRONMENTS**

## Configuration

Activate the local JWT profile by adding `local-jwt` to your Spring active profiles:

```
SPRING_PROFILES_ACTIVE=local,local-jwt
```

This sets `server.ssl.client-auth=want`, `jwt.issuer=http://localhost:3000`, and
`jwt.test-secret=izg-test-secret-igdd-2705-do-not-use-in-production`.

## Test Token

| Field | Value |
|---|---|
| Secret | `izg-test-secret-igdd-2705-do-not-use-in-production` |
| Kid | `00000000-0000-0000-0000-000000000001` |
| Algorithm | HS256 |
| Issuer | `http://localhost:3000` |
| Subject (jurisdictionId) | `"42"` (numeric string — matches legacy IZG jurisdiction ID scheme) |
| JTI | `018f4e2a-5678-7abc-8def-000000000002` |
| Environments (server-side on the DynamoDB record — NOT a JWT claim) | `[5]` (Development) |
| UPN | `test.example.gov` |
| Roles | `ads`, `soap` — **inert, see note below** |
| Expires | 2038-01-19 (far future — dev only) |

> **The `roles` claim is ignored by the Hub.** The `jwt-upn-authorization` change removed
> JWT-claim roles: `ApiKeyPrincipal` carries no roles, and `AccessControlValve` resolves
> roles from the DynamoDB AccessGroup table keyed on `principal.getName()` (the `upn`).
> The claim is left in the snippets below only because it is present in previously issued
> tokens; new tokens need not include it. To exercise a protected endpoint, provision
> `test.example.gov` into an AccessGroup carrying the roles you need (`soap` for the SOAP
> endpoints; `users` plus an `ads`-prefixed group for ADS/DEX).

**Token:** regenerate using the Node.js snippet below (`dns` claim replaced by `upn`; the previous token is no longer valid).

## Example curl

Before using this token, create the matching `ApiKeyCredential` record in your local DynamoDB.

`environments` MUST be a **Number Set (`NS`)** and `useTypes` a **String Set (`SS`)** — these are the
attribute types Config Console writes and the Hub bean expects (IGDD-3140). A List (`L`) will not
deserialize. Shown in DynamoDB wire format so the types are unambiguous:

```json
{
  "entityType":     { "S": "ApiKeyCredential" },
  "sortKey":        { "S": "018f4e2a-5678-7abc-8def-000000000002" },
  "jti":            { "S": "018f4e2a-5678-7abc-8def-000000000002" },
  "environments":   { "NS": ["5"] },
  "useTypes":       { "SS": ["PROVIDER"] },
  "status":         { "S": "active" },
  "jurisdictionId": { "S": "42" },
  "issuedAt":       { "S": "2025-06-04T00:00:00Z" },
  "expiresAt":      { "S": "2038-01-19T00:00:00Z" }
}
```

(`NS` values are quoted on the wire — that is DynamoDB's precision-preserving encoding for numbers,
the same reason a scalar number is `{"N": "5"}`. The set above really does contain the number 5.)

Save that as `cred.json` and load it with:

```bash
aws dynamodb put-item --endpoint-url http://localhost:8000 \
  --table-name izgateway-dev --item file://cred.json
```

`useTypes` is only needed to exercise the routing-time use-type check (IGDD-3257): the credential's
`useTypes` must intersect the **destination** jurisdiction's `allowedUseTypes`, so the destination's
`Jurisdiction` record needs a matching `"allowedUseTypes": { "SS": ["PROVIDER"] }`. A mismatch is
always rejected with a `SecurityFault` (HTTP 400 on the REST/ADS path; the SOAP path returns 500 with a
SOAP Fault envelope) — there is no warn mode. A jurisdiction with **no** `allowedUseTypes` attribute
denies every API-key sender, so you must add it to test the happy path; DynamoDB cannot store an empty
set, so absence is how deny-all is expressed.

Then call the Hub REST endpoint:

```bash
TOKEN=$(node -e "
const jwt = require('jsonwebtoken');
console.log(jwt.sign(
  { iss:'http://localhost:3000', sub:'42', jti:'018f4e2a-5678-7abc-8def-000000000002',
    iat:Math.floor(Date.now()/1000), exp:Math.floor(Date.now()/1000)+(365*24*3600),
    upn:'test.example.gov', roles:['ads','soap'] },
  'izg-test-secret-igdd-2705-do-not-use-in-production',
  { algorithm:'HS256', header:{ kid:'00000000-0000-0000-0000-000000000001' } }
));
")
curl -k https://localhost:8443/rest/health \
  -H "Authorization: Bearer $TOKEN"
```

## Generating a New Test Token

If you need a new token with different claims, use the following Node.js snippet:

```javascript
const jwt = require('jsonwebtoken');
const token = jwt.sign(
  {
    iss: 'http://localhost:3000',
    sub: '42',
    jti: crypto.randomUUID(),
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + (365 * 24 * 3600),
    upn: 'test.example.gov',
    roles: ['ads', 'soap']
  },
  'izg-test-secret-igdd-2705-do-not-use-in-production',
  { algorithm: 'HS256', header: { kid: '00000000-0000-0000-0000-000000000001' } }
);
console.log(token);
```
