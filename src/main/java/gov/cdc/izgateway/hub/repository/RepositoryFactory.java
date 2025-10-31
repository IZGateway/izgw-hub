package gov.cdc.izgateway.hub.repository;

/**
 * Repository Factory is used to support replacable repositories.
 * IZ Gateway supports repositories in DynamoDB and has used databases 
 * previously in RDS/MySQL.
 * <p>
 * Rather than pass in specific repositories to services, this class
 * is used so that the service can request the appropriate repository
 * and auto-generated classes such as those created from Spring's 
 * JpaRepository can be accessed based on configuration parameters.
 * </p>
 * <p>
 * This interface provides access to all supported repository types.
 * </p>
 *
 * @author Audacious Inquiry
 */
public interface RepositoryFactory {
    /**
     * Get the Repository for Access Controls.
     * @return The AccessControlRepository
     */
    IAccessControlRepository accessControlRepository();

    /**
     * Get the Repository for Certificate Status.
     * @return The CertificateStatusRepository
     */
    ICertificateStatusRepository certificateStatusRepository();

    /**
     * Get the Repository for Destinations.
     * @return The DestinationRepository
     */
    IDestinationRepository destinationRepository();

    /**
     * Get the Repository for Jurisdictions.
     * @return The JurisdictionRepository
     */
    IJurisdictionRepository jurisdictionRepository();

    /**
     * Get the Repository for Message Headers.
     * @return The MessageHeaderRepository
     */
    IMessageHeaderRepository messageHeaderRepository();

    /**
     * Get the Repository for Access Groups.
     * @return The AccessGroupRepository
     */
    IAccessGroupRepository accessGroupRepository();

    /**
     * Get the Repository for Allowed Users.
     * @return The AllowedUserRepository
     */
    IAllowedUserRepository allowedUserRepository();

    /**
     * Get the Repository for Deny List Records.
     * @return The DenyListRecordRepository
     */
    IDenyListRecordRepository denyListRecordRepository();

    /**
     * Get the Repository for File Types.
     * @return The FileTypeRepository
     */
    IFileTypeRepository fileTypeRepository();

    /**
     * Get the Repository for Organization Records.
     * @return The OrganizationRecordRepository
     */
    IOrganizationRecordRepository organizationRecordRepository();
}