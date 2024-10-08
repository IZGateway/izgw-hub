
package gov.cdc.izgateway.db.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import gov.cdc.izgateway.db.model.CertificateStatus;
import gov.cdc.izgateway.repository.ICertificateStatusRepository;

/*
 * PhizRevocationChecker runs in a separate thread that won't
 * have a transaction.  Using propagation=Propagation.REQUIRES_NEW
 * will ensure that a new transaction is created.
 */
@Repository
@Transactional
public interface CertificateStatusRepository extends JpaRepository<CertificateStatus, Integer>, ICertificateStatusRepository {
    CertificateStatus findByCertificateId(String certificateId);
}
