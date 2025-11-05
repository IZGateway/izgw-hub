package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.CertificateStatus;
import gov.cdc.izgateway.hub.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for CertificateStatus.
 * 
 * @author Audacious Inquiry
 */
public class CertificateStatusRepository extends DynamoDbRepository<CertificateStatus> implements ICertificateStatusRepository {
	/**
	 * Construct a new CertificateStatusRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table name
	 */
	public CertificateStatusRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(CertificateStatus.class, client, tableName);
	}
	
	@Override
	public ICertificateStatus store(ICertificateStatus cert) {
		if (cert == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		if (cert instanceof CertificateStatus c) {
			return saveAndFlush(c);
		} 
		return saveAndFlush(new CertificateStatus(cert));
	}

	@Override
	public ICertificateStatus findByCertificateId(String certificateId) {
		return this.find(certificateId);
	}
}
