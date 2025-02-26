package gov.cdc.izgateway.dynamodb;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.ServiceConfigurationError;

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
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;

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

	private static final String DATABASE_EVENT = "Database";
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
	 * @param ddbClient The DynamoDB Client to use
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
			Event e = markDbCreated(); 
			if (e != null) {
				migrateAllTables();
				e.setCompleted(new Date());
				eventRepository.update(e);
			} else {
				waitForMigrationToFinish();
			}
		} else {
			log.info("Connected to existing {} in {}", DynamoDbRepository.TABLE_NAME, 
					DefaultAwsRegionProviderChain.builder().build().getRegion());
		}
	}

	private void waitForMigrationToFinish() throws ServiceConfigurationError {
		List<Event> found = null;
		long sleep = 0;
		int retries = 10;
		do {
			try {
				if (sleep != 0) {
					Thread.sleep(sleep);
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			sleep = 30000;
			found = eventRepository.findByNameAndTarget(Event.CREATED, DATABASE_EVENT); 
		}	while ((found.isEmpty() || found.get(0).getCompleted() == null) && --retries > 0);
		if (retries <= 0) {
			log.error("Failed waiting for DB migration on {} in {}", DynamoDbRepository.TABLE_NAME, 
					DefaultAwsRegionProviderChain.builder().build().getRegion());
			throw new ServiceConfigurationError("Database not migrated: " + DynamoDbRepository.TABLE_NAME, null);
		}
	}

	private boolean ensureDbExists() {
		ListTablesRequest lgtr = ListTablesRequest.builder().build();
		ListTablesResponse response = ddbClient.listTables(lgtr);
		if (response.hasTableNames() && response.tableNames().stream().anyMatch(DynamoDbRepository.TABLE_NAME::equals)) {
			if (!isDbCreated()) {
				return false;
			}
			log.info("Connected to existing database: {}", DynamoDbRepository.TABLE_NAME);
			return true;
		}
		log.error("Database does not exist: {}", DynamoDbRepository.TABLE_NAME);
		throw new ServiceConfigurationError("Database does not exist " + DynamoDbRepository.TABLE_NAME, null);
	}

	private boolean isDbCreated() {
		return !eventRepository.findByNameAndTarget(Event.CREATED, DATABASE_EVENT).isEmpty();
	}
	
	private Event markDbCreated() {
		Event event = new Event(Event.CREATED);
		event.setTarget(DATABASE_EVENT);
		return eventRepository.create(event);
	}

	private void migrateAllTables() {
		if (migrationFactory != null) {
			acr = new AccessControlRepository(client);
			migrateTo(migrationFactory.accessControlRepository(), acr);
			// Migrating the CertificateStatus repository is not required. Any unchecked certificate will be rechecked.
			jr = new JurisdictionRepository(client);
			migrateTo(migrationFactory.jurisdictionRepository(), jr);
			dr = new DestinationRepository(client);
			migrateTo(migrationFactory.destinationRepository(), dr);
			mhr = new MessageHeaderRepository(client);
			migrateTo(migrationFactory.messageHeaderRepository(), mhr);
			log.info("Database Migration completed for {}", DynamoDbRepository.TABLE_NAME);
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
		eventRepository.update(event);
		log.info("Migration complete for {}", dest.getClass().getSimpleName());
		return dest;
	}

	private Event startMigrationEvent(IRepository<?> dest) {
		String eventTarget = dest.getClass().getSimpleName();
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
		Event event = new Event(Event.MIGRATION, eventTarget);
		return eventRepository.create(event);
	}
}