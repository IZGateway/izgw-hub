package gov.cdc.izgateway.db.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.Jurisdiction;
import gov.cdc.izgateway.repository.IJurisdictionRepository;

@Repository
public interface JurisdictionRepository extends JpaRepository<Jurisdiction, String>, IJurisdictionRepository {     }