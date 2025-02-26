
package gov.cdc.izgateway.db.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import gov.cdc.izgateway.db.model.CertificateStatus;
import gov.cdc.izgateway.hub.repository.ICertificateStatusRepository;
import gov.cdc.izgateway.model.ICertificateStatus;

/**
 * Interface for the CertificateStatusRepository
 *
 * @author Audacious Inquiry
 */
@Repository
@Transactional
public interface CertificateStatusRepository extends JpaRepository<CertificateStatus, Integer>, ICertificateStatusRepository {
    CertificateStatus findByCertificateId(String certificateId);
    /**
     * Save and flush the access control record.
     * @param control	The record to save
     * @return	The saved record
     */
	@Override
	default ICertificateStatus store(ICertificateStatus h) {
		if (h instanceof CertificateStatus cs) {
			return saveAndFlush(cs);
		}
		return saveAndFlush(new CertificateStatus(h));
	}
}
