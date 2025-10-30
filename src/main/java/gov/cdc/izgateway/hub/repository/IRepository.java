package gov.cdc.izgateway.hub.repository;

import java.util.List;
import java.util.ServiceConfigurationError;

/** 
 * Marker interface for repositories (e.g., DynamoDb, JPA or other database technology)
 * @author Audacious Inquiry
 * @param <T> The type of item the repository stores
 *
 */
public interface IRepository<T> {
	/**
	 * Get all entities in the repository.
	 * @return a List of entities in the repository.
	 */
	List<? extends T> findAll();
	/**
	 * 
	 * @param h	The entity to store
	 * @return	The stored entity (may be of a different class if from different repositories).
	 */
	T store(T h);
	
	/**
	 * Copy a list of data from one place to another.
	 * @param list	The list to copy
	 */
	default void migrate(List<? extends T> list) {
		try {
			for (T e: list) {
				store(e);
			}
		} catch (Exception e) {
			throw new ServiceConfigurationError("Failed to migrate " + this.getClass().getSimpleName(), e);
		}
	}

}
