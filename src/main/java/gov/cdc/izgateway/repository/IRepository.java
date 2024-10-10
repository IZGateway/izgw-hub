package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.IMessageHeader;

/** 
 * Marker interface for repositories (Dynamo or JPA)
 * @author Audacious Inquiry
 * @param <T> The type of item the repository stores
 *
 */
public interface IRepository<T> {
	List<? extends T> findAll();
	T saveAndFlush(T h);
}
