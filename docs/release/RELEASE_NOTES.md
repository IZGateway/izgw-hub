# IZ Gateway Release 2.16.0

- IGDD-2679 — Fix unexpected exception detected in izgw-hub (shared with Xform Service)
- IGDD-2711 — Implement the grace-period revocation scheduled job
- IGDD-2805 — Fix Source Attack Exception lockout not firing (a detected source attack never actually deny-listed the sender), and add a per-sender exception mechanism (`hub.source-attack-lockout.enabled`, default off; `/rest/sourceAttackExceptions` admin API) so known false positives (e.g., a patient name containing "javascript") don't get a sender locked out.
- IGDD-2806 — Fix `TC_MOCK_02b` failure in staging (the test case was not found after a recent patch)
- IGDD-3002 — Fix the izgw-bom nightly dependency-update workflow failure on the "Send email notification" step (shared with Xform Service)
- IGDD-3084 — Fix the Swagger UI 404 in IZG Hub
- IGDD-3089 — Make Security Fault errors reportable in IZG Onboarding logs
- IGDD-3167 — Update the grace-period sweeper to set Expired and Revoked distinctly
- IGDD-3257 — Enhance API-key authentication with useTypes
- IGDD-3308 — Add Z42 mock response data for ImmunizationRecommendation testing (shared with Xform Service)
- IGDD-3372 — Skip client certificate validation when an API key is present

