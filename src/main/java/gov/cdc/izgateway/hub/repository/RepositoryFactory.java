package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.CertificateStatus;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.dynamodb.model.Jurisdiction;
import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.dynamodb.model.OrganizationRecord;
import gov.cdc.izgateway.dynamodb.repository.EventRepository;

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
    IAccessControlRepository<AccessControl> accessControlRepository();

    /**
     * Get the Repository for Certificate Status.
     * @return The CertificateStatusRepository
     */
    ICertificateStatusRepository<CertificateStatus> certificateStatusRepository();

    /**
     * Get the Repository for Destinations.
     * @return The DestinationRepository
     */
    IDestinationRepository<Destination> destinationRepository();

    /**
     * Get the Repository for Jurisdictions.
     * @return The JurisdictionRepository
     */
    IJurisdictionRepository<Jurisdiction> jurisdictionRepository();

    /**
     * Get the Repository for Message Headers.
     * @return The MessageHeaderRepository
     */
    IMessageHeaderRepository<MessageHeader> messageHeaderRepository();

    /**
     * Get the Repository for Access Groups.
     * @return The AccessGroupRepository
     */
    IAccessGroupRepository<AccessGroup> accessGroupRepository();

    /**
     * Get the Repository for Allowed Users.
     * @return The AllowedUserRepository
     */
    IAllowedUserRepository<AllowedUser> allowedUserRepository();

    /**
     * Get the Repository for Deny List Records.
     * @return The DenyListRecordRepository
     */
    IDenyListRecordRepository<DenyListRecord> denyListRecordRepository();

    /**
     * Get the Repository for File Types.
     * @return The FileTypeRepository
     */
    IFileTypeRepository<FileType> fileTypeRepository();

    /**
     * Get the Repository for Organization Records.
     * @return The OrganizationRecordRepository
     */
    IOrganizationRecordRepository<OrganizationRecord> organizationRecordRepository();
    
    /**
     * Get the Repository for Event Logs.
     * @return The EventRepository
     */
    EventRepository eventRepository();
}