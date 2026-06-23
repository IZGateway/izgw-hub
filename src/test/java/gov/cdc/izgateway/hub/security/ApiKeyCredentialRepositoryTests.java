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
    void findByEnvAndJti_missingRecord_returnsEmpty() {
        when(repository.findByEnvAndJti("Production", "unknown-jti")).thenReturn(Optional.empty());

        Optional<ApiKeyCredential> result = repository.findByEnvAndJti("Production", "unknown-jti");

        assertThat(result).isEmpty();
    }

    @Test
    void findByEnvAndJti_existingRecord_returnsPresent() {
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(repository.findByEnvAndJti("Production", "test-jti")).thenReturn(Optional.of(cred));

        Optional<ApiKeyCredential> result = repository.findByEnvAndJti("Production", "test-jti");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("active");
    }

    @Test
    void sortKeyFormat_isCorrect() {
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setEnv("Production");
        cred.setJti("018f4e2a-5678-7abc-8def-000000000002");

        assertThat(cred.getPrimaryId()).isEqualTo("Production#018f4e2a-5678-7abc-8def-000000000002");
    }

    @Test
    void sortKeyFormat_worksForNonProductionEnvironments() {
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setEnv("Onboarding");
        cred.setJti("018f4e2a-5678-7abc-8def-000000000003");

        assertThat(cred.getPrimaryId()).isEqualTo("Onboarding#018f4e2a-5678-7abc-8def-000000000003");
    }
}
