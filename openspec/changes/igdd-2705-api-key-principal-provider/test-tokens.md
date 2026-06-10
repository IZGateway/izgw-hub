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
| Subject (jurisdictionId) | `TEST` |
| JTI | `018f4e2a-5678-7abc-8def-000000000002` |
| Environment | `Development` |
| DNS | `test.example.gov` |
| Roles | `ads`, `soap` |
| Expires | 2038-01-19 (far future — dev only) |

**Token:**
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSJ9.eyJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjMwMDAiLCJzdWIiOiJURVNUIiwianRpIjoiMDE4ZjRlMmEtNTY3OC03YWJjLThkZWYtMDAwMDAwMDAwMDAyIiwiaWF0IjoxNzQ4OTA4ODAwLCJleHAiOjIxNDU5MTY4MDAsImRucyI6InRlc3QuZXhhbXBsZS5nb3YiLCJyb2xlcyI6WyJhZHMiLCJzb2FwIl0sImVudiI6IkRldmVsb3BtZW50In0.L5gEEAVFytlmZbLHNLq2vwRBjHzRtZ9qHr5TsXzKI4w
```

## Example curl

Before using this token, create the matching `ApiKeyCredential` record in your local DynamoDB:

```json
{
  "entityType": "ApiKeyCredential",
  "sortKey": "Development#018f4e2a-5678-7abc-8def-000000000002",
  "jti": "018f4e2a-5678-7abc-8def-000000000002",
  "env": "Development",
  "status": "active",
  "jurisdictionId": "TEST",
  "issuedAt": "2025-06-04T00:00:00Z",
  "expiresAt": "2038-01-19T00:00:00Z"
}
```

Then call the Hub REST endpoint:

```bash
curl -k https://localhost:8443/rest/health \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSJ9.eyJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjMwMDAiLCJzdWIiOiJURVNUIiwianRpIjoiMDE4ZjRlMmEtNTY3OC03YWJjLThkZWYtMDAwMDAwMDAwMDAyIiwiaWF0IjoxNzQ4OTA4ODAwLCJleHAiOjIxNDU5MTY4MDAsImRucyI6InRlc3QuZXhhbXBsZS5nb3YiLCJyb2xlcyI6WyJhZHMiLCJzb2FwIl0sImVudiI6IkRldmVsb3BtZW50In0.L5gEEAVFytlmZbLHNLq2vwRBjHzRtZ9qHr5TsXzKI4w"
```

## Generating a New Test Token

If you need a new token with different claims, use the following Node.js snippet:

```javascript
const jwt = require('jsonwebtoken');
const token = jwt.sign(
  {
    iss: 'http://localhost:3000',
    sub: 'TEST',
    jti: crypto.randomUUID(),
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + (365 * 24 * 3600),
    dns: 'test.example.gov',
    roles: ['ads', 'soap'],
    env: 'Development'
  },
  'izg-test-secret-igdd-2705-do-not-use-in-production',
  { algorithm: 'HS256', header: { kid: '00000000-0000-0000-0000-000000000001' } }
);
console.log(token);
```
