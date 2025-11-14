package gov.cdc.izgateway.hub.service.accesscontrol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import com.opencsv.exceptions.CsvValidationException;

import gov.cdc.izgateway.Application;
import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.Event;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.dynamodb.model.OrganizationRecord;
import gov.cdc.izgateway.dynamodb.repository.AccessGroupRepository;
import gov.cdc.izgateway.dynamodb.repository.AllowedUserRepository;
import gov.cdc.izgateway.dynamodb.repository.DenyListRecordRepository;
import gov.cdc.izgateway.dynamodb.repository.EventRepository;
import gov.cdc.izgateway.dynamodb.repository.FileTypeRepository;
import gov.cdc.izgateway.dynamodb.repository.OrganizationRecordRepository;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.IOrganizationRecordRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.repository.IRepository;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;

/**
 * Implements the IAccessControlService for IZ Gateway.
 * 
 * 
 * @author Audacious Inquiry
 */
@Slf4j
@Service
public class AccessControlMigrator {

	private final IAccessControlRepository<AccessControl> accessControlRepository;
    private final IAccessGroupRepository<AccessGroup> accessGroupRepository;
    private final IAllowedUserRepository<AllowedUser> allowedUserRepository;
    private final IDenyListRecordRepository<DenyListRecord> denyListRecordRepository;
    private final IFileTypeRepository<FileType> fileTypeRepository;
    private final IOrganizationRecordRepository<OrganizationRecord> organizationRecordRepository;
	private final EventRepository eventRepository;
	private final List<IRepository<?>> repositoriesToMigrate;

	@Value("${server.hostname:dev.izgateway.org}")
	private String serverName;
	@Value("${hub.migration-data:access-controls.csv}")
	private String migrationData;
	
    /**
     * Create a new AccessControlService
     * @param factory The repository factory to use
     */
    @Autowired
    public AccessControlMigrator(RepositoryFactory factory) {
        this.accessControlRepository = factory.accessControlRepository();
        this.accessGroupRepository = factory.accessGroupRepository();
        this.allowedUserRepository = factory.allowedUserRepository();
        this.denyListRecordRepository = factory.denyListRecordRepository();
        this.fileTypeRepository = factory.fileTypeRepository();
        this.organizationRecordRepository = factory.organizationRecordRepository();
        factory.destinationRepository();
        this.eventRepository = factory.eventRepository();
        factory.certificateStatusRepository();
        this.repositoriesToMigrate = Arrays.asList(accessGroupRepository, allowedUserRepository, denyListRecordRepository, fileTypeRepository, organizationRecordRepository);
    }
    
	/**
     * Configure service to update itself periodically after initialization.
	 * @return True if migration was performed or already migrated, false if migration was skipped or failed.
     */
    public boolean checkForMigration() {
    	Boolean[] furtherMigrationNeeded = { false };
    	try {
    		DynamoDbRepository.setServerName(serverName);
    		if (!Application.isSkipMigrations()) { // Prevent unit tests from causing migrations
				repositoriesToMigrate.stream().filter(r -> r.findAll().isEmpty()).forEach(r -> {
					log.info("Migrating data to {}", r.getClass().getSimpleName());
					furtherMigrationNeeded[0] |= migrateToNewAccessControlModel(r);
				});
	    		migrateFromCSV();
	    		return true;
			}
	    	return false;
    	} catch (ServiceConfigurationError e) {
    		log.error(Markers2.append(e), "Error during Access Control migration: {}", e.getMessage());
    		return false;  // Use old model access control data if migration failed.
    	}
    }
    
    /**
     * Migrate existing access control records to the new model.
     * 
     * @param r The repository to migrate to
     * @throws ServiceConfigurationError if there is an error during migration
     */
	private boolean migrateToNewAccessControlModel(IRepository<?> r) {
		// To force a re-migration, delete "Migration" events for the specified repository type in DynamoDb 
		Event migrationEvent = eventRepository.create(new Event(Event.MIGRATION, r.getClass().getSimpleName())); 
		if (migrationEvent == null) {
			log.info("Migration already performed for {}", r.getClass().getSimpleName());
			return false;
		}
		
		List<Event> l = eventRepository.findByNameAndTarget("Created", "Database");
		Event creationEvent = l.isEmpty() ? migrationEvent : l.get(0);
		String reportedBy = serverName + "@" + creationEvent.getReportedBy();
		List<String> adsUsers = List.of("ads", "adspilot");
		boolean success = false;
		try {
			List<? extends IAccessControl> recordsToMigrate = accessControlRepository.findAll();
			if (r instanceof AccessGroupRepository agr) {
				agr.migrateAccessControls(recordsToMigrate.stream()
					.filter(ac -> IAccessControlService.GROUP_CATEGORY.equals(ac.getCategory()))		// It's a group
					.filter(ac -> !"blacklist".equals(ac.getName()) &&  // but not the blacklist
								  !adsUsers.contains(ac.getName()))		// Or an ADS User group
					.toList(),
					reportedBy, 
					migrationEvent.getStarted(),
					getEnvironmentsToPopulate()
				);
				success = true;
				return true;
			} 
			if (r instanceof AllowedUserRepository aur) {
				// Only ADS Users use access controls today
				aur.migrateAccessControls(recordsToMigrate.stream()
						.filter(ac -> IAccessControlService.GROUP_CATEGORY.equals(ac.getCategory()))		// It's a group
						.filter(ac -> adsUsers.contains(ac.getName()))		// It's an ADS User group
						.toList(), 
						reportedBy, 
						migrationEvent.getStarted()
				);
				success = true;
				return true;
			} 
			if (r instanceof DenyListRecordRepository dlr) {
				dlr.migrateAccessControls(recordsToMigrate.stream()
					.filter(ac -> IAccessControlService.GROUP_CATEGORY.equals(ac.getCategory()))		// It's a group
					.filter(ac -> "blacklist".equals(ac.getName()))		// it's the denylist
					.toList(), 
					reportedBy, 
					migrationEvent.getStarted()
				);
				success = true;
				return true;
			} 
			if (r instanceof FileTypeRepository ftr) {
				List<FileType> fileTypes = recordsToMigrate.stream()
					.filter(ac -> IAccessControlService.ROUTE_CATEGORY.equals(ac.getCategory()))		// It's a group
					.map(ac -> new FileType(ac, migrationEvent.getReportedBy(), migrationEvent.getCompleted())).toList();
				fileTypes.forEach(ft -> { ft.setCreatedBy(migrationEvent.getReportedBy()); ft.setCreatedOn(migrationEvent.getStarted()); });
				ftr.migrate(fileTypes);
				success = true;
				return true;
			} 
			if (r instanceof OrganizationRecordRepository) {
				// No migration needed for Organization Records
				success = true;
				return true;
			} 
			log.error("Unrecognized repository type for migration: {}", r.getClass().getSimpleName());
			throw new ServiceConfigurationError("Unrecognized repository type for migration: " + r.getClass().getSimpleName());
		} finally {
			migrationEvent.setCompleted(new Date());
			if (success) {
				eventRepository.update(migrationEvent);
			} else {
				eventRepository.delete(migrationEvent);
			}
		}
	}
	
	private static int[] getEnvironmentsToPopulate() throws ServiceConfigurationError {
		switch (SystemUtils.getDestType()) {
		case SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD:
			return new int[] { SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD };
		case SystemUtils.DESTTYPE_STAGE:
			return new int[] { SystemUtils.DESTTYPE_STAGE };
		case SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST:
			return new int[] { SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST };
		default:
			throw new ServiceConfigurationError("Unknown environment type: " + SystemUtils.getDestType());
		}
	}

	/**
	 * Migrate access controls from access-controls.csv to allowed users.
	 * @throws ServiceConfigurationError if there is an error reading the CSV file
	 */
	private boolean migrateFromCSV() throws ServiceConfigurationError {
		Event migrationEvent = eventRepository.create(new Event(Event.MIGRATION, "ImportAllowedUsers")); 
		boolean success = false;
		if (migrationEvent == null) {
			log.info("Migration already performed for ImportAllowedUsers");
			success = true;
			return false;
		}
		try (
			FileReader isr = new FileReader(migrationData, StandardCharsets.UTF_8);
			CSVReader csvr = new CSVReaderBuilder(isr).withSkipLines(1).withFieldAsNull(CSVReaderNullFieldIndicator.BOTH).build();
		) {
			int[] environments = getEnvironmentsToPopulate();
			
			createAllowedUsers(csvr, environments);
			addDevOpsPrincipals(csvr);
			addUsersGroup(environments);
			success = true;
			return true;
		} catch (Exception e) {
			log.error(Markers2.append(e), "Error reading access-controls.csv");
			throw new ServiceConfigurationError("Error reading access-controls.csv", e);
		} finally {
			if (success) {
				log.info("Migration to Allowed Users from CSV completed successfully");
				migrationEvent.setCompleted(new Date());
				eventRepository.update(migrationEvent);
			} else {
				log.info("Migration to Allowed Users from CSV failed");
				eventRepository.delete(migrationEvent);
			}
		}
	}

	private void createAllowedUsers(CSVReader csvr, int[] environments) throws IOException {
		Map<String, OrganizationRecord> orgMap = new LinkedHashMap<>();
		Map<String, AllowedUser> allowedUserMap = new LinkedHashMap<>();
		String[] row;
		try {
			while (true) {
				// Type,Source,Can Access,Id,Organization Name,Onboarding Cert Common Name,Prod Cert Common Name,Other Cert 1,Other Cert 2
				try {
					row = csvr.readNext();
				} catch (CsvValidationException e) {
					log.error(Markers2.append(e), "CSV is invalid for access-controls.csv at line: {}", e.getLineNumber());
					continue;
				}
				if (row == null || row[0] == null) {
					return;
				}
				createOrganizationAndUser(environments, orgMap, allowedUserMap, row);
			}
		} finally {
			organizationRecordRepository.migrate(orgMap.values());
			allowedUserRepository.migrate(allowedUserMap.values());
		}
	}

	private void createOrganizationAndUser(int[] environments, Map<String, OrganizationRecord> orgMap,
			Map<String, AllowedUser> allowedUserMap, String[] row) {
		// Type,Source,Can Access,Id,Organization Name,Onboarding Cert Common Name,Prod Cert Common Name,Other Cert 1,Other Cert 2
		String type = row[0];
		String orgName = row.length < 1 ? null : row[1];
		String destinationId = row.length < 3 ? null : row[3];
		String[] finalRow = row;
		orgMap.computeIfAbsent(orgName, 
				k -> createOrgRecord(type, orgName, Arrays.asList(finalRow).subList(5, finalRow.length)));
		
		for (int env : environments) {
			List<Integer> certsToProcess = null;
			if (List.of(SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_DEV).contains(env)) {
				certsToProcess = List.of(6);
			} else {
				certsToProcess = List.of(5, 7, 8);
			}
			for (int certToProcess : certsToProcess) {
				String certCommonName = row.length <= certToProcess ? null : row[certToProcess];
				if (certCommonName == null || certCommonName.isBlank()) {
					continue;
				}
				String key = String.format("%s#%s#%s", env, destinationId, certCommonName);
				allowedUserMap.computeIfAbsent(key, k -> createAllowedUserRecord(env, destinationId, certCommonName));				
			}
		}
	}
	

	private void addUsersGroup(int[] environments) {
		for (int env: environments) {
			AccessGroup g = accessGroupRepository.findByTypeAndName(env, Roles.USERS);
			if (g == null) {
				g = new AccessGroup();
				g.setEnvironment(env);
				g.setGroupName(Roles.USERS);
				g.getRoles().add(Roles.USERS);
			}
			// Add the wildcard user to the users group
			g.getUsers().add("*");
			accessGroupRepository.store(g);
		}
		
	}

	private static class CertLists {
		Set<String> monitoringCert = new LinkedHashSet<>();
		Set<String> devOpsStaff = new LinkedHashSet<>();
		Set<String> preprodCerts = new LinkedHashSet<>();
		Set<String> onboardingCerts = new LinkedHashSet<>();
		Set<String> prodCerts = new LinkedHashSet<>();
		Set<String> developmentCerts = new LinkedHashSet<>();
	}
	private void addDevOpsPrincipals(CSVReader csvr) throws IOException  {
		CertLists certLists = collectOperationsUserData(csvr);
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_STAGE) {
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_STAGE, Roles.SOAP);
			addSystemCerts(certLists.devOpsStaff, SystemUtils.DESTTYPE_STAGE, Roles.ADMIN);
			addSystemCerts(certLists.preprodCerts, SystemUtils.DESTTYPE_STAGE, Roles.INTERNAL);
			addToDenyList(certLists.onboardingCerts, SystemUtils.DESTTYPE_STAGE);
			addToDenyList(certLists.prodCerts, SystemUtils.DESTTYPE_STAGE);
			addToDenyList(certLists.developmentCerts, SystemUtils.DESTTYPE_STAGE);
		}
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_ONBOARD || SystemUtils.getDestType() == SystemUtils.DESTTYPE_PROD) {
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_PROD, Roles.SOAP);
			addSystemCerts(certLists.devOpsStaff, SystemUtils.DESTTYPE_PROD, Roles.ADMIN);
			addSystemCerts(certLists.onboardingCerts, SystemUtils.DESTTYPE_PROD, Roles.INTERNAL);
			addToDenyList(certLists.preprodCerts, SystemUtils.DESTTYPE_PROD);
			addToDenyList(certLists.prodCerts, SystemUtils.DESTTYPE_PROD);
			addToDenyList(certLists.developmentCerts, SystemUtils.DESTTYPE_PROD);
			
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_ONBOARD, Roles.SOAP);
			addSystemCerts(certLists.devOpsStaff, SystemUtils.DESTTYPE_ONBOARD, Roles.ADMIN);
			addSystemCerts(certLists.prodCerts, SystemUtils.DESTTYPE_ONBOARD, Roles.INTERNAL);
			addToDenyList(certLists.preprodCerts, SystemUtils.DESTTYPE_ONBOARD);
			addToDenyList(certLists.onboardingCerts, SystemUtils.DESTTYPE_ONBOARD);
			addToDenyList(certLists.developmentCerts, SystemUtils.DESTTYPE_ONBOARD);
		}
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_DEV || SystemUtils.getDestType() == SystemUtils.DESTTYPE_TEST) {
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_DEV, Roles.SOAP);
			addSystemCerts(certLists.devOpsStaff, SystemUtils.DESTTYPE_DEV, Roles.ADMIN);
			addSystemCerts(certLists.developmentCerts, SystemUtils.DESTTYPE_DEV, Roles.INTERNAL);
			addToDenyList(certLists.preprodCerts, SystemUtils.DESTTYPE_DEV);
			addToDenyList(certLists.onboardingCerts, SystemUtils.DESTTYPE_DEV);
			addToDenyList(certLists.prodCerts, SystemUtils.DESTTYPE_DEV);
			
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_TEST, Roles.SOAP);
			addSystemCerts(certLists.monitoringCert, SystemUtils.DESTTYPE_TEST, Roles.SOAP);
			addSystemCerts(certLists.devOpsStaff, SystemUtils.DESTTYPE_TEST, Roles.ADMIN);
			addSystemCerts(certLists.developmentCerts, SystemUtils.DESTTYPE_TEST, Roles.INTERNAL);
			addToDenyList(certLists.preprodCerts, SystemUtils.DESTTYPE_TEST);
			addToDenyList(certLists.onboardingCerts, SystemUtils.DESTTYPE_TEST);
			addToDenyList(certLists.prodCerts, SystemUtils.DESTTYPE_TEST);
		}
	}

	private CertLists collectOperationsUserData(CSVReader csvr) throws IOException {
		CertLists certLists = new CertLists();
		try {
			// Skip header line
			csvr.readNext();
		} catch (CsvValidationException e) {
			log.error(Markers2.append(e), "CSV malformed for access-controls.csv at line: {}", e.getLineNumber());
		}
		String[] row;
		while (true) {
			// Type,Principal,Organization
			try {
				row = csvr.readNext();
			} catch (CsvValidationException e) {
				log.error(Markers2.append(e), "CSV is invalid for access-controls.csv at line: {}", e.getLineNumber());
				continue;
			}
			if (row == null || row.length < 2) {
				return certLists;
			}
			String type = row[0];
			String principal = row.length < 2 ? null : row[1];
			String organization = row.length < 3 ? null : row[2];
			createOrganization(type, principal, organization);
			switch (type.toLowerCase()) {
			case "monitoring":	certLists.monitoringCert.add(principal); break;
			case "staff":		certLists.devOpsStaff.add(principal); break;
			case "preprod":		certLists.preprodCerts.add(principal); break;
			case "onboarding":	certLists.onboardingCerts.add(principal); break;
			case "prod":		certLists.prodCerts.add(principal); break;
			case "development":	certLists.developmentCerts.add(principal); break;
			default:
				log.error("Unrecognized type {} in access-controls.csv", type);
			}
		}
	}

	private void createOrganization(String type, String principal, String organization) {
		OrganizationRecord orgRecord = this.organizationRecordRepository.find(organization);
		if (orgRecord == null) {
			orgRecord = createOrgRecord(type, organization, List.of(principal));
		} else if (!orgRecord.getPrincipalNames().contains(principal)) {
			orgRecord.addPrincipalName(principal);
		}
		organizationRecordRepository.store(orgRecord);
	}

	/**
	 * Give the specified principals access to IZ Gateway REST and SOAP APIs  
	 * @param principalsToAllow	The principals to allow
	 * @param destType	The destination type they are being allowed to
	 * @param role The role to assign
	 */
	private void addSystemCerts(Collection<String> principalsToAllow, int destType, String role) {
		AccessGroup g = accessGroupRepository.findByTypeAndName(destType, role);
		if (g == null) {
			g = new AccessGroup();
			g.setEnvironment(destType);
			g.setGroupName(role);
			g.getRoles().add(role);
			accessGroupRepository.store(g);
		}
		g.getUsers().addAll(principalsToAllow);
		accessGroupRepository.store(g);
	}

	/**
	 * Given the specified principals, add them to the deny list.
	 * @param principalsToDeny	The principals to deny
	 * @param destType	The destination type they are being denied from
	 */
	private void addToDenyList(Set<String> principalsToDeny, int destType) {
		for (String principal : principalsToDeny) {
			DenyListRecord dlr = new DenyListRecord();
			dlr.setEnvironment(destType);
			dlr.setPrincipal(principal);
			denyListRecordRepository.store(dlr);
		}
	}

	private OrganizationRecord createOrgRecord(String type, String orgName, Collection<String> finalRow) {
		OrganizationRecord org = new OrganizationRecord();
		org.setType(type);
		org.setOrganizationName(orgName);
		for (String certCn : finalRow) {
			if (certCn != null && !certCn.isBlank()) {
				org.addPrincipalName(certCn);
			}
		}
		return org;
	}
	
	private AllowedUser createAllowedUserRecord(int env, String destinationId, String principal) {
		AllowedUser u = new AllowedUser();
		u.setEnvironment(env);
		u.setDestinationId(destinationId);
		u.setPrincipal(principal);
		u.setEnabled(true);
		u.setValidatedOn(u.getUpdatedOn());
		return u;
	}
}