package gov.cdc.izgateway.dynamodb.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.dynamodb.model.CertificateStatus;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.service.ICertificateStatusService;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;


@Service
public class CertificateStatusService implements ICertificateStatusService {

	@Override
	public List<ICertificateStatus> getAllCertificates() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ICertificateStatus save(ICertificateStatus certificateStatus) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ICertificateStatus findByCertificateId(String certificateId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void refresh() {
		// TODO Auto-generated method stub
	}

	@Override
	public ICertificateStatus create(X509Certificate cert) {
		// TODO Auto-generated method stub
		return new CertificateStatus(cert);
	}
}
