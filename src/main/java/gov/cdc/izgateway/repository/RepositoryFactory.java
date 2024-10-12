package gov.cdc.izgateway.repository;

/**
 * Repository Factory is used to support replacable repositories.
 * IZ Gateway supports repositories in MySQL/RDS and in DynamoDB.
 * 
 * Rather than pass in specific repositories to services, this class
 * is used so that the service can request the appropriate repository
 * and auto-generated classes such as those created from spring's JpaRepository
 * can be accessed based on configuration parameters.
 * 
 * @author Audacious Inquiry
 */
public interface RepositoryFactory {
    /**
     * Get the Repository for Access Controls
     * @return	The AccessControlRepository
     */
    IAccessControlRepository accessControlRepository();

	/**
     * Get the Repository for Certificate Status
     * @return	The CertificateStatusRepository
     */
    public ICertificateStatusRepository certificateStatusRepository();

    
	/**
     * Get the Repository for Destinations
     * @return	The DestinationRepository
     */
    public IDestinationRepository destinationRepository();

	/**
     * Get the Repository for Jurisdictions
     * @return	The JurisdictionRepository
     */
    public IJurisdictionRepository jurisdictionRepository();

	/**
     * Get the Repository for Message Headers
     * @return	The MessageHeaderRepository
     */
    public IMessageHeaderRepository messageHeaderRepository();
}
