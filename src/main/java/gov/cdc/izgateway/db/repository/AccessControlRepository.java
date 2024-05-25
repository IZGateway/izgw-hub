package gov.cdc.izgateway.db.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.AccessControl;
import gov.cdc.izgateway.db.model.AccessControlId;

@Repository
public interface AccessControlRepository extends JpaRepository<AccessControl, AccessControlId> {
    }