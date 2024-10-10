package gov.cdc.izgateway.dynamodb;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gov.cdc.izgateway.dynamodb.repository.AccessControlRepository;
import gov.cdc.izgateway.dynamodb.repository.CertificateStatusRepository;
import gov.cdc.izgateway.dynamodb.repository.DestinationRepository;
import gov.cdc.izgateway.dynamodb.repository.JurisdictionRepository;
import gov.cdc.izgateway.dynamodb.repository.MessageHeaderRepository;
import gov.cdc.izgateway.repository.IAccessControlRepository;
import gov.cdc.izgateway.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.repository.IDestinationRepository;
import gov.cdc.izgateway.repository.IJurisdictionRepository;
import gov.cdc.izgateway.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.repository.IRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Creates the necessary Beans for DynamoDB repository access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb') or '${spring.database:}'.equalsIgnoreCase('migrate')")
@Configuration
@Slf4j
public class DynamoDbRepositoryFactory {

	private final DynamoDbEnhancedClient client;
	private final boolean isMigration;

	/**
	 * Create the factory for DynamoDb Repositories
	 * @param client The Enhanced Client to use to access the repository
	 * @param database The type of database initialization to create
	 */
	public DynamoDbRepositoryFactory(
		@Autowired DynamoDbEnhancedClient client, 
		@Value("${spring.database:}") String database
	) {
		this.client = client;
		this.isMigration = "migrate".equalsIgnoreCase(database);
	}
	
    /**
     * Get the DynamoDbRepository for Access Controls
     * @return	The AccessControlRepository
     */
    @Bean
    @ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb')")
    public IAccessControlRepository accessControlRepository() {
    	return newAccessControlRepository(client);
    }

    private static IAccessControlRepository newAccessControlRepository(DynamoDbEnhancedClient client2) {
		return new AccessControlRepository(client2);
	}

	/**
     * Get the DynamoDbRepository for Certificate Status
     * @return	The CertificateStatusRepository
     */
    @Bean
    @ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb')")
    public ICertificateStatusRepository certificateStatusRepository() {
    	return newCertificateStatusRepository(client);
    }

    
    private static ICertificateStatusRepository newCertificateStatusRepository(DynamoDbEnhancedClient client2) {
		return new CertificateStatusRepository(client2);
	}

	/**
     * Get the DynamoDbRepository for Destinations
     * @return	The DestinationRepository
     */
    @Bean
    @ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb')")
    public IDestinationRepository destinationRepository() {
    	return newDestinationRepository(client);
    }

    private static IDestinationRepository newDestinationRepository(DynamoDbEnhancedClient client2) {
		return new DestinationRepository(client2);
	}

	/**
     * Get the DynamoDbRepository for Jurisdictions
     * @return	The JurisdictionRepository
     */
    @Bean
    @ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb')")
    public IJurisdictionRepository jurisdictionRepository() {
    	return newJurisdictionRepository(client);
    }

    private static IJurisdictionRepository newJurisdictionRepository(DynamoDbEnhancedClient client2) {
		return new JurisdictionRepository(client2) ;
	}

	/**
     * Get the DynamoDbRepository for Message Headers
     * @return	The MessageHeaderRepository
     */
    @Bean
    @ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb')")
    public IMessageHeaderRepository messageHeaderRepository() {
    	return newMessageHeaderRepository(client);
    }
    
    private static IMessageHeaderRepository newMessageHeaderRepository(DynamoDbEnhancedClient client2) {
		return new MessageHeaderRepository(client2);
	}

	public static <T extends IRepository> T migrate(T source, DynamoDbEnhancedClient client) {
    	if (source instanceof DynamoDbRepository) {
    		return source;
    	}
    	
    	if (source instanceof IAccessControlRepository acr) {
    		IAccessControlRepository dest = newAccessControlRepository(client);
    		return copy(acr, dest);
    	} 
    	if (source instanceof ICertificateStatusRepository csr) {
    		ICertificateStatusRepository dest = newCertificateStatusRepository(client);
    		return copy(csr, dest);
    	} 
    	if (source instanceof IDestinationRepository dr) {
    		IDestinationRepository dest = newDestinationRepository(client);
    		return copy(dr, dest);
    	} 
    	if (source instanceof IJurisdictionRepository jr) {
    		IJurisdictionRepository dest = newJurisdictionRepository(client);
    		return copy(jr, dest);
    	} 
    	
    	if (source instanceof IMessageHeaderRepository mhr) {
    		IMessageHeaderRepository dest = newMessageHeaderRepository(client);
    		return copy(mhr, dest);
    	}
    	// Figure out which kind of repository we need and get it.
    	// Check for an existing migration already done.
    	// Do the migration to the new repository
    	// return the new repository
    }

	private static <T> IRepository<T> copy(IRepository<T> source, IRepository<T> dest) {
		for (T e: source.findAll()) {
			dest.saveAndFlush(e);
		}
		return dest;
	}
}