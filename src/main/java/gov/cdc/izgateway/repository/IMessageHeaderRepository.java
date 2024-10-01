package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.IMessageHeader;

public interface IMessageHeaderRepository {

	List<? extends IMessageHeader> findAll();

	IMessageHeader saveAndFlush(IMessageHeader h);

}
