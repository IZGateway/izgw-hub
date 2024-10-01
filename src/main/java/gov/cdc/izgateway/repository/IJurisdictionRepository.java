package gov.cdc.izgateway.repository;

import java.util.List;
import java.util.Map;

import gov.cdc.izgateway.db.model.Jurisdiction;
import gov.cdc.izgateway.model.IJurisdiction;

public interface IJurisdictionRepository {

	List<? extends IJurisdiction> findAll();

}
