package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.ICertificateStatus;

public interface ICertificateStatusRepository extends IRepository<ICertificateStatus> {

	List<? extends ICertificateStatus> findAll();
	ICertificateStatus findByCertificateId(String certificateId);

}
