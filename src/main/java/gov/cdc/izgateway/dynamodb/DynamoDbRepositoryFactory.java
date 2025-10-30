package gov.cdc.izgateway.dynamodb;
import gov.cdc.izgateway.configuration.DynamoDbConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ServiceConfigurationError;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import gov.cdc.izgateway.dynamodb.model.Event;
import gov.cdc.izgateway.dynamodb.repository.AccessControlRepository;
import gov.cdc.izgateway.dynamodb.repository.CertificateStatusRepository;
import gov.cdc.izgateway.dynamodb.repository.DestinationRepository;
import gov.cdc.izgateway.dynamodb.repository.EventRepository;
import gov.cdc.izgateway.dynamodb.repository.JurisdictionRepository;
import gov.cdc.izgateway.dynamodb.repository.MessageHeaderRepository;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.hub.repository.IDestinationRepository;
import gov.cdc.izgateway.hub.repository.IJurisdictionRepository;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;

/**
 * Creates the necessary Beans for DynamoDB repository access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@Configuration
@Primary
@Slf4j
public class DynamoDbRepositoryFactory implements RepositoryFactory {

	private static final String DATABASE_EVENT = "Database";
	private final String tableName;
	private final DynamoDbEnhancedClient client;
	// Used to check for existence of the database
	private final DynamoDbClient ddbClient;
	private EventRepository eventRepository;
	private AccessControlRepository acr;
	private CertificateStatusRepository csr;
	private DestinationRepository dr;
	private JurisdictionRepository jr;
	private MessageHeaderRepository mhr;

	/**
	 * Create the factory for DynamoDb Repositories
	 * @param client The Enhanced Client to use to access the repository
	 * @param ddbClient The DynamoDB Client to use
	 * @param ddbConfig The DynamoDB configuration
	 */
	public DynamoDbRepositoryFactory(
		@Autowired DynamoDbEnhancedClient client, 
		@Autowired DynamoDbClient ddbClient,
		@Autowired DynamoDbConfig ddbConfig
	) {
		this.client = client;
		this.ddbClient = ddbClient;
		this.tableName = ddbConfig.getDynamodbTable();
		this.eventRepository = new EventRepository(client, this.tableName);
		
		if (!ensureDbExists()) {
			log.error("Database {} does not exist in {}", this.tableName,
					DefaultAwsRegionProviderChain.builder().build().getRegion());
			throw new ServiceConfigurationError("Database does not exist " + this.tableName, null);
		} else {
			log.info("Connected to existing {} in {}", this.tableName,
					DefaultAwsRegionProviderChain.builder().build().getRegion());
		}
	}

	private boolean ensureDbExists() {
		ListTablesRequest lgtr = ListTablesRequest.builder().build();
		ListTablesResponse response = ddbClient.listTables(lgtr);
		if (response.hasTableNames() && response.tableNames().stream().anyMatch(this.tableName::equals)) {
			if (!isDbCreated()) {
				return false;
			}
			log.info("Connected to existing database: {}", this.tableName);
			return true;
		}
		log.error("Database does not exist: {}", this.tableName);
		throw new ServiceConfigurationError("Database does not exist " + this.tableName, null);
	}

	private boolean isDbCreated() {
		return !eventRepository.findByNameAndTarget(Event.CREATED, DATABASE_EVENT).isEmpty();
	}
	
	/**
     * Get the DynamoDbRepository for Access Controls
     * @return	The AccessControlRepository
     */
    public IAccessControlRepository accessControlRepository() {
		if (acr == null) {
    		acr = new AccessControlRepository(client, this.tableName);
    	}
    	return acr;
    }

	/**
     * Get the DynamoDbRepository for Certificate Status
     * @return	The CertificateStatusRepository
     */
    public ICertificateStatusRepository certificateStatusRepository() {
    	if (csr == null) {
    		csr = new CertificateStatusRepository(client, this.tableName);
    	}
    	return csr;
    }

    
	/**
     * Get the DynamoDbRepository for Destinations
     * @return	The DestinationRepository
     */
    public IDestinationRepository destinationRepository() {
    	if (dr == null) {
    		dr = new DestinationRepository(client, this.tableName);
    	}
    	return dr;
    }

	/**
     * Get the DynamoDbRepository for Jurisdictions
     * @return	The JurisdictionRepository
     */
    public IJurisdictionRepository jurisdictionRepository() {
    	if (jr == null) {
    		jr = new JurisdictionRepository(client, this.tableName);
    	}
    	return jr;
    }

	/**
     * Get the DynamoDbRepository for Message Headers
     * @return	The MessageHeaderRepository
     */
    public IMessageHeaderRepository messageHeaderRepository() {
    	if (mhr == null) {
    		mhr = new MessageHeaderRepository(client, this.tableName);
    	}
    	return mhr;
    }
    
    /**
     * Get the event repository.  As this is new with DynamoDb support,
     * no migration is necessary.
     * 
     * @return	The event repository.
     */
    public EventRepository eventRepository() {
    	return eventRepository;
    }
}
