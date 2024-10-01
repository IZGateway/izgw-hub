package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.IDestination;

public interface IDestinationRepository {

	List<? extends IDestination> findAll();

	void saveAndFlush(IDestination dest);

}
