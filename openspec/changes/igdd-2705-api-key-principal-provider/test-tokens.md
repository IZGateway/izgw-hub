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

Before using this token, create the matching `ApiKeyCredential` record in your local DynamoDB:

```json
{
  "entityType": "ApiKeyCredential",
  "sortKey": "018f4e2a-5678-7abc-8def-000000000002",
  "jti": "018f4e2a-5678-7abc-8def-000000000002",
  "environments": [5],
  "status": "active",
  "jurisdictionId": "42",
  "issuedAt": "2025-06-04T00:00:00Z",
  "expiresAt": "2038-01-19T00:00:00Z"
}
```

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
