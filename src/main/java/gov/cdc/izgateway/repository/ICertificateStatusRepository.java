package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.ICertificateStatus;

public interface ICertificateStatusRepository {

	List<? extends ICertificateStatus> findAll();

	ICertificateStatus saveAndFlush(ICertificateStatus certificateStatus);

	ICertificateStatus findByCertificateId(String certificateId);

}
