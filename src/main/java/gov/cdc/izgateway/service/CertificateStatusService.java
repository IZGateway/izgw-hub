package gov.cdc.izgateway.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.db.model.CertificateStatus;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.repository.ICertificateStatusRepository;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;


@Service
public class CertificateStatusService implements ICertificateStatusService {
    private final ICertificateStatusRepository certificateStatusRepository;
    
    @Autowired
    public CertificateStatusService(ICertificateStatusRepository certificateStatusRepository) {
        this.certificateStatusRepository = certificateStatusRepository;
    }
    
    @Override
	public List<ICertificateStatus> getAllCertificates() {
        return new ArrayList<>(certificateStatusRepository.findAll());
    }

    @Override
	public ICertificateStatus save(ICertificateStatus certificateStatus){
		return certificateStatusRepository.saveAndFlush(certificateStatus);
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
