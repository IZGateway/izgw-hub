package gov.cdc.izgateway.dynamodb;
import java.net.URI;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gov.cdc.izgateway.common.HealthService;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.utils.StringUtils;

/**
 * Creates the necessary clients for DynamoDB access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@Configuration
@NoArgsConstructor
@Slf4j
public class DynamoDbConfig {
    /**
     * Create a DynamoDB Client
     * @param dynamodbEndpoint The endpoint to connect to.
     * @return The client
     */
    @Bean
    public DynamoDbClient getDynamoDbClient(
    	@Value("${amazon.dynamodb.endpoint:https://localhost:8000/}") 
    	String dynamodbEndpoint
    ) {
    	DynamoDbClientBuilder builder = DynamoDbClient
                .builder()
                .region(DefaultAwsRegionProviderChain.builder().build().getRegion())
                .credentialsProvider(DefaultCredentialsProvider.create()); 
    	HealthService.setDatabase(dynamodbEndpoint + DynamoDbRepository.TABLE_NAME);
        if (StringUtils.isNotBlank(dynamodbEndpoint)) {
            builder.endpointOverride(URI.create(dynamodbEndpoint));
            log.info("DynamoDB Client initialized to {}", dynamodbEndpoint);
        }
        return builder.build();
    }

    /**
     * Create an enhanced DynamoDB Client
     * @param ddbc The basic client to build the enhanced client from
     * @return	The enhanced client
     */
    @Bean
    public DynamoDbEnhancedClient getDynamoDbEnhancedClient(DynamoDbClient ddbc) {
        return DynamoDbEnhancedClient
                .builder()
                .extensions(VersionedRecordExtension.builder().build())
                .dynamoDbClient(ddbc)
                .build();
    }
}