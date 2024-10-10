package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.CertificateStatus;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.repository.ICertificateStatusRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for CertificateStatus.
 * 
 * @author Audacious Inquiry
 */
@Repository
public class CertificateStatusRepository extends DynamoDbRepository<CertificateStatus> implements ICertificateStatusRepository {
	/**
	 * Construct a new CertificateStatusRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public CertificateStatusRepository(@Autowired DynamoDbEnhancedClient client) {
		super(CertificateStatus.class, client);
	}
	
	@Override
	public ICertificateStatus saveAndFlush(ICertificateStatus cert) {
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
