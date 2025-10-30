package gov.cdc.izgateway.hub.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.dynamodb.model.CertificateStatus;
import gov.cdc.izgateway.hub.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.service.ICertificateStatusService;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;


/**
 * Service for managing certificate statuses for Revocation checking.
 * @author Audacious Inquiry
 *
 */
@Service
public class CertificateStatusService implements ICertificateStatusService {
    private final ICertificateStatusRepository certificateStatusRepository;
    
    /**
     * Constructor
     * @param factory	The RepositoryFactory to use to create repositories.
     */
    @Autowired
    public CertificateStatusService(RepositoryFactory factory) {
        this.certificateStatusRepository = factory.certificateStatusRepository();
    }
    
    @Override
	public List<ICertificateStatus> getAllCertificates() {
        return new ArrayList<>(certificateStatusRepository.findAll());
    }

    @Override
	public ICertificateStatus save(ICertificateStatus certificateStatus){
		return certificateStatusRepository.store(certificateStatus);
    }
    
    @Override
	public ICertificateStatus findByCertificateId(String certificateId) {
    	return certificateStatusRepository.findByCertificateId(certificateId);
    }

	@Override
	public void refresh() {
	}

	@Override
	public ICertificateStatus create(X509Certificate cert) {
		return new CertificateStatus(cert);
	}
}
