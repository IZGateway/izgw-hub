## 1. Remove JWT-claim roles from ApiKeyPrincipal

- [x] 1.1 Remove `Collection<String> jwtRoles` parameter from `ApiKeyPrincipal` constructor (`src/main/java/gov/cdc/izgateway/hub/security/ApiKeyPrincipal.java`)
- [x] 1.2 Remove the `setRoles(new TreeSet<>(jwtRoles))` call from the constructor body

## 2. Remove roles extraction from ApiKeyPrincipalProvider

- [x] 2.1 Remove the `roles` local variable declaration and `claims.getStringListClaim("roles")` call from `lookupAndCacheCredential()` (`src/main/java/gov/cdc/izgateway/hub/security/ApiKeyPrincipalProvider.java`)
- [x] 2.2 Update the `new ApiKeyPrincipal(...)` call to remove the `roles` argument

## 3. Remove AccessControlService fallback for JWT-claim roles

- [x] 3.1 Remove the `ApiKeyPrincipal` instanceof check and its `getRoles().contains(role)` fallback from `AccessControlService.isUserInRole()` (`src/main/java/gov/cdc/izgateway/hub/service/accesscontrol/AccessControlService.java`)
- [x] 3.2 Remove the `import gov.cdc.izgateway.hub.security.ApiKeyPrincipal` import from `AccessControlService.java` if it is no longer referenced

## 4. Update tests

- [x] 4.1 Update `ApiKeyPrincipalTests.java`: remove the `roles` parameter from the `principal(List<String> roles)` helper and update all callers to use the simplified constructor (`src/test/java/gov/cdc/izgateway/hub/security/ApiKeyPrincipalTests.java`)
- [x] 4.2 Remove or update the `rolesAreReturnedInTheFormatAccessControlValveExpects()` test in `ApiKeyPrincipalTests.java` — roles are always empty on `ApiKeyPrincipal`; replace with an assertion that `getRoles()` returns an empty set
- [x] 4.3 Update `ApiKeyPrincipalProviderTests.java`: remove the `roles` claim from the JWT builder (line ~73) and replace the `assertThat(apiKey.getRoles()).contains("ads", "soap")` assertion (line ~103) with an assertion that `getRoles()` is empty (`src/test/java/gov/cdc/izgateway/hub/security/ApiKeyPrincipalProviderTests.java`)

## 5. Verify

- [x] 5.1 Run `mvn test` and confirm all tests pass
