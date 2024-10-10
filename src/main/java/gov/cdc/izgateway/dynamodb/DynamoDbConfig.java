package gov.cdc.izgateway.dynamodb;
import java.net.URI;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gov.cdc.izgateway.dynamodb.repository.DestinationRepository;
import gov.cdc.izgateway.repository.IDestinationRepository;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.utils.StringUtils;

/**
 * Creates the necessary clients for DynamoDB access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb') or '${spring.database:}'.equalsIgnoreCase('migrate')")
@Configuration
@NoArgsConstructor
@Slf4j
public class DynamoDbConfig {
    @Value("${amazon.dynamodb.endpoint:https://localhost:8000/}")
    private String dynamodbEndpoint;

    /**
     * Create a DynamoDB Client
     * @return The client
     */
    @Bean
    public DynamoDbClient getDynamoDbClient() {
    	DynamoDbClientBuilder builder = DynamoDbClient  // NOSONAR, use region from EC2 or environment
                .builder()
                .credentialsProvider(DefaultCredentialsProvider.create()); 

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