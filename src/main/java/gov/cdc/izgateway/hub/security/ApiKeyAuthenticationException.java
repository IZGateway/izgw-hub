package gov.cdc.izgateway.hub.security;

/**
 * Thrown by {@link ApiKeyPrincipalProvider} when a request presents a Bearer-scheme
 * Authorization header but the token fails authentication for any reason (malformed,
 * unverifiable signature, expired, revoked, unknown, or a dependency failure that prevents
 * validation from completing).
 *
 * <p>{@link gov.cdc.izgateway.hub.service.HubPrincipalService} uses this to distinguish
 * "no API key was presented" (still eligible for certificate fallback) from "an API key was
 * presented but rejected" (must not fall back to certificate authentication).
 */
public class ApiKeyAuthenticationException extends RuntimeException {
    public ApiKeyAuthenticationException(String message) {
        super(message);
    }
}
