package gov.cdc.izgateway.db.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.db.model.CertificateStatus;
import gov.cdc.izgateway.db.repository.CertificateStatusRepository;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.service.ICertificateStatusService;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;


@Service
public class CertificateStatusService implements ICertificateStatusService {
    private final CertificateStatusRepository certificateStatusRepository;
    
    @Autowired
    public CertificateStatusService(CertificateStatusRepository certificateStatusRepository) {
        this.certificateStatusRepository = certificateStatusRepository;
    }
    
    @Override
	public List<ICertificateStatus> getAllCertificates() {
        return new ArrayList<>(certificateStatusRepository.findAll());
    }

    @Override
	public ICertificateStatus save(ICertificateStatus certificateStatus){
    	if (certificateStatus instanceof CertificateStatus s) {
    		return certificateStatusRepository.saveAndFlush(s);
    	} else {
    		CertificateStatus s = new CertificateStatus(certificateStatus);
    		return certificateStatusRepository.saveAndFlush(s);
    	}
    }
    
    @Override
	public ICertificateStatus findByCertificateId(String certificateId) {
    	return certificateStatusRepository.findByCertificateId(certificateId);
    }

	@Override
	public void refresh() {
		// Do nothing, since no refresh needed on this one.
	}

	@Override
	public ICertificateStatus create(X509Certificate cert) {
		return new CertificateStatus(cert);
	}
}
