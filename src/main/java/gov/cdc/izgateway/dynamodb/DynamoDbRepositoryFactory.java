package gov.cdc.izgateway.dynamodb;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import gov.cdc.izgateway.db.repository.MySqlRepositoryFactory;
import gov.cdc.izgateway.dynamodb.model.Event;
import gov.cdc.izgateway.dynamodb.repository.AccessControlRepository;
import gov.cdc.izgateway.dynamodb.repository.CertificateStatusRepository;
import gov.cdc.izgateway.dynamodb.repository.DestinationRepository;
import gov.cdc.izgateway.dynamodb.repository.EventRepository;
import gov.cdc.izgateway.dynamodb.repository.JurisdictionRepository;
import gov.cdc.izgateway.dynamodb.repository.MessageHeaderRepository;
import gov.cdc.izgateway.repository.IAccessControlRepository;
import gov.cdc.izgateway.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.repository.IDestinationRepository;
import gov.cdc.izgateway.repository.IJurisdictionRepository;
import gov.cdc.izgateway.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.repository.IRepository;
import gov.cdc.izgateway.repository.RepositoryFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the necessary Beans for DynamoDB repository access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('dynamodb') or '${spring.database:}'.equalsIgnoreCase('migrate')")
@Configuration
@Primary
@Slf4j
public class DynamoDbRepositoryFactory implements RepositoryFactory {

	private final DynamoDbEnhancedClient client;
	// Used to check for existence of the database
	private final DynamoDbClient ddbClient;
	private EventRepository eventRepository;
	private MySqlRepositoryFactory migrationFactory;
	private AccessControlRepository acr;
	private CertificateStatusRepository csr;
	private DestinationRepository dr;
	private JurisdictionRepository jr;
	private MessageHeaderRepository mhr;

	/**
	 * Create the factory for DynamoDb Repositories
	 * @param client The Enhanced Client to use to access the repository
	 * @param migrationFactory The factory to use to access other (MySql) databases to support migration.
	 */
	public DynamoDbRepositoryFactory(
		@Autowired DynamoDbEnhancedClient client, 
		@Autowired DynamoDbClient ddbClient,
		@Autowired(required=false) MySqlRepositoryFactory migrationFactory
	) {
		this.client = client;
		this.ddbClient = ddbClient;
		this.migrationFactory = migrationFactory;
		this.eventRepository = new EventRepository(client);
		
		if (!ensureDbExists()) {
			markDbCreated();
			migrateAllDbs();
		} else {
			log.info("Connected to existing {} in {}", DynamoDbRepository.TABLE_NAME, 
					DefaultAwsRegionProviderChain.builder().build().getRegion());
		}
	}

	private boolean ensureDbExists() {
		ListTablesRequest lgtr = ListTablesRequest.builder().build();
		boolean failed = false;
		ListTablesResponse response = ddbClient.listTables(lgtr);
		if (response.hasTableNames() && response.tableNames().stream().anyMatch(DynamoDbRepository.TABLE_NAME::equals)) {
			if (!isDbCreated()) {
				return false;
			}
			log.info("Connected to existing database {}", DynamoDbRepository.TABLE_NAME);
			return true;
		}
		log.warn("DynamoDb Table {} does not exist, attempting to create it", DynamoDbRepository.TABLE_NAME);
		// Attempt to create the database
		CreateTableRequest.Builder cgtr = CreateTableRequest.builder();
		cgtr.tableName(DynamoDbRepository.TABLE_NAME)
			//.deletionProtectionEnabled(true)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.keySchema(
				KeySchemaElement.builder().attributeName("entityType").keyType(KeyType.HASH).build(),
				KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build()
			)
			.attributeDefinitions(
				AttributeDefinition.builder().attributeName("entityType").attributeType(ScalarAttributeType.S).build(),
				AttributeDefinition.builder().attributeName("sortKey").attributeType(ScalarAttributeType.S).build()
			);
		try {
			ddbClient.createTable(cgtr.build());
			return false;
		} catch (ResourceInUseException rex) {
			if (failed) {
				log.error("Cannot create existing database {}", DynamoDbRepository.TABLE_NAME);
				throw rex;
			}
		} catch (Exception ex) {
			log.error("Error creating database {}", DynamoDbRepository.TABLE_NAME);
			throw ex;
		}
		return false;
	}

	private boolean isDbCreated() {
		return eventRepository.findByNameAndTarget(Event.CREATED, "Database").isEmpty();
	}
	private void markDbCreated() {
		Event event = new Event(Event.CREATED);
		event.setTarget("Database");
		eventRepository.store(event);
	}

	private void migrateAllDbs() {
		if (migrationFactory != null) {
			migrateTo(migrationFactory.accessControlRepository(), acr = new AccessControlRepository(client));
			// Migrating the CertificateStatus repository is not required. Any unchecked certificate will be rechecked.
			migrateTo(migrationFactory.jurisdictionRepository(), jr = new JurisdictionRepository(client));
			migrateTo(migrationFactory.destinationRepository(), dr = new DestinationRepository(client));
			migrateTo(migrationFactory.messageHeaderRepository(), mhr = new MessageHeaderRepository(client));
		}
	}
	
    /**
     * Get the DynamoDbRepository for Access Controls
     * @return	The AccessControlRepository
     */
    public IAccessControlRepository accessControlRepository() {
		if (acr == null) {
    		acr = new AccessControlRepository(client);
    	}
    	return acr;
    }

	/**
     * Get the DynamoDbRepository for Certificate Status
     * @return	The CertificateStatusRepository
     */
    public ICertificateStatusRepository certificateStatusRepository() {
    	if (csr == null) {
    		csr = new CertificateStatusRepository(client);
    	}
    	return csr;
    }

    
	/**
     * Get the DynamoDbRepository for Destinations
     * @return	The DestinationRepository
     */
    public IDestinationRepository destinationRepository() {
    	if (dr == null) {
    		dr = new DestinationRepository(client);
    	}
    	return dr;
    }

	/**
     * Get the DynamoDbRepository for Jurisdictions
     * @return	The JurisdictionRepository
     */
    public IJurisdictionRepository jurisdictionRepository() {
    	if (jr == null) {
    		jr = new JurisdictionRepository(client);
    	}
    	return jr;
    }

	/**
     * Get the DynamoDbRepository for Message Headers
     * @return	The MessageHeaderRepository
     */
    public IMessageHeaderRepository messageHeaderRepository() {
    	if (mhr == null) {
    		mhr = new MessageHeaderRepository(client);
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
    
	private <T, R extends IRepository<T>> R migrateTo(R source, R dest) {
		if (source == null) {
			return dest;
		}
		Event event = startMigrationEvent(dest);
		if (event == null) {
			return dest;
		}
		// We don't really need to check for concurrency here
		// because two migrating servers should do the same exact thing.
		for (T e: source.findAll()) {
			dest.store(e);
		}
		event.setCompleted(new Date());
		eventRepository.store(event);
		log.info("Migration complete for {}", dest.getClass().getSimpleName());
		return dest;
	}

	private Event startMigrationEvent(IRepository<?> dest) {
		String eventTarget = dest.getClass().getName();
		Duration waitFor = Duration.ofMinutes(5);
		Duration waitPeriod = Duration.ofSeconds(10);
		while (eventRepository.hasEventStarted(Event.MIGRATION, eventTarget)) {
			if (eventRepository.hasEventFinished(Event.MIGRATION, eventTarget)) {
				return null;
			}
			if (waitFor.toSeconds() <= 0) {
				// If we are done waiting, retry the migration by this server
				log.warn("Migration incomplete by: {}", eventRepository.findByNameAndTarget(Event.MIGRATION, eventTarget));
				break;
			}
			try {
				Thread.sleep(waitPeriod.toMillis());
			} catch (InterruptedException e1) {
				Thread.currentThread().interrupt();
			}
			waitFor = waitFor.minus(waitPeriod);
		}
		// If we get to this stage, we've either waited long enough for a started event to complete
		// or it hasn't started yet.  So, we'll say that migration is needed.  It is still POSSIBLE
		// that two servers could try to migrate the data after this call.
		Event event = new Event(Event.MIGRATION);
		return eventRepository.store(event);
	}
}