package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.util.Optional;

public class ApiKeyCredentialRepository extends DynamoDbRepository<ApiKeyCredential> {

    public ApiKeyCredentialRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(ApiKeyCredential.class, client, tableName);
    }

    public Optional<ApiKeyCredential> findByEnvAndJti(String env, String jti) {
        return Optional.ofNullable(find(env + "#" + jti));
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
