# IZ Gateway Release 2.3.0
The IZ Gateway Hub 2.3.0 release
* IGDD-1988 - Adding logic to provide a way to bypass the SecretHeaderFilter if the request URI matches one of the configured paths.  This is useful for healthchecks that cannot attach an http header with the secret.
* IGDD-1986 Fix to TLS test issue when IZG Hub pipeline runs.
* IGDD-2006 Address two HIGH CVE's before 2.3.0 release - CVE-2025-22235, CVE-2025-30706

