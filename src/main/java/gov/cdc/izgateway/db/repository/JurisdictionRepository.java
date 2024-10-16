package gov.cdc.izgateway.db.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.Jurisdiction;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.repository.IJurisdictionRepository;

/**
 * The interface for the MySql Jurisdiction repository
 * @author Audacious Inquiry
 */
@Repository
public interface JurisdictionRepository extends JpaRepository<Jurisdiction, String>, IJurisdictionRepository {

	@Override
	default IJurisdiction store(IJurisdiction j) {
		if (j instanceof Jurisdiction jj) {
			return saveAndFlush(jj);
		} 
		return saveAndFlush(new Jurisdiction(j));
	} 
	
}