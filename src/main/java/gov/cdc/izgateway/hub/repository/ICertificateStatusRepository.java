package gov.cdc.izgateway.hub.repository;

import java.util.List;

import gov.cdc.izgateway.model.ICertificateStatus;

/**
 * A repository tracking certificate status information.
 * @author Audacious Inquiry
 *
 */
public interface ICertificateStatusRepository extends IRepository<ICertificateStatus> {

	@Override
	List<? extends ICertificateStatus> findAll();
	/**
	 * Find a given certificate by its ID.
	 * @param certificateId	The certificate ID
	 * @return	The certificate status or null if not found
	 */
	ICertificateStatus findByCertificateId(String certificateId);

}
