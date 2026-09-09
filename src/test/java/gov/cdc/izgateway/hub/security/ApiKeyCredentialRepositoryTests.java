package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyCredentialRepositoryTests {

    @Mock
    private ApiKeyCredentialRepository repository;

    @Test
    void findByJti_missingRecord_returnsEmpty() {
        when(repository.findByJti("unknown-jti")).thenReturn(Optional.empty());

        Optional<ApiKeyCredential> result = repository.findByJti("unknown-jti");

        assertThat(result).isEmpty();
    }

    @Test
    void findByJti_existingRecord_returnsPresent() {
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(repository.findByJti("test-jti")).thenReturn(Optional.of(cred));

        Optional<ApiKeyCredential> result = repository.findByJti("test-jti");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("active");
    }

    @Test
    void sortKeyFormat_isJtiAlone() {
        // The sort key is the jti alone — no environment prefix (IGDD-3140).
        String jti = "018f4e2a-5678-7abc-8def-000000000002";
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setJti(jti);
        assertThat(cred.getPrimaryId()).isEqualTo(jti);
        assertThat(cred.getSortKey()).isEqualTo(jti);
    }
}
