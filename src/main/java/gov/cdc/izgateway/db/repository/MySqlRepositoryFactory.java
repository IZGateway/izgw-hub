package gov.cdc.izgateway.db.repository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.hub.repository.IDestinationRepository;
import gov.cdc.izgateway.hub.repository.IJurisdictionRepository;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;

/**
 * Creates the necessary Beans for DynamoDB repository access
 * These are the clients that are used to access any DynamoDB repositories
 * @author Audacious Inquiry
 */
@ConditionalOnExpression("'${spring.database:}'.equalsIgnoreCase('jpa') or '${spring.database:}'.equalsIgnoreCase('migrate')")
@Configuration
@Slf4j
public class MySqlRepositoryFactory implements RepositoryFactory {

	@Getter
	private final boolean forMigration;
	private AccessControlRepository acr;
	private CertificateStatusRepository csr;
	private DestinationRepository dr;
	private JurisdictionRepository jr;
	private MessageHeaderRepository mhr;

	/**
	 * Create the factory for MySql Repositories
	 * @param acr The Access Control repository
	 * @param csr The Certificate Status repository
	 * @param dr  The Destination repository
	 * @param jr  The Jurisdiction repository
	 * @param mhr The Message Header repository
	 * @param database The type of database initialization to create
	 */
	public MySqlRepositoryFactory(
		@Autowired
		AccessControlRepository acr,
		@Autowired
		CertificateStatusRepository csr,
		@Autowired
		DestinationRepository dr,
		@Autowired
		JurisdictionRepository jr,
		@Autowired
		MessageHeaderRepository mhr,
		@Value("${spring.database:}") String database
	) {
		this.acr = acr;
		this.csr = csr;
		this.dr = dr;
		this.jr = jr;
		this.mhr = mhr;
		this.forMigration = "migrate".equalsIgnoreCase(database);
	}
	
    /**
     * Get the MySqlRepository for Access Controls
     * @return	The AccessControlRepository
     */
    public IAccessControlRepository accessControlRepository() {
    	return acr;
    }

	/**
     * Get the MySqlRepository for Certificate Status
     * @return	The CertificateStatusRepository
     */
    public ICertificateStatusRepository certificateStatusRepository() {
    	return csr;
    }

    
	/**
     * Get the MySqlRepository for Destinations
     * @return	The DestinationRepository
     */
    public IDestinationRepository destinationRepository() {
    	return dr;
    }

	/**
     * Get the MySqlRepository for Jurisdictions
     * @return	The JurisdictionRepository
     */
    public IJurisdictionRepository jurisdictionRepository() {
    	return jr;
    }

	/**
     * Get the MySqlRepository for Message Headers
     * @return	The MessageHeaderRepository
     */
    public IMessageHeaderRepository messageHeaderRepository() {
    	return mhr;
    }
}