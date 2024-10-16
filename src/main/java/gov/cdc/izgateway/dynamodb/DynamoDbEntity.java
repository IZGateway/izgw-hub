package gov.cdc.izgateway.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * This class is used to mark DynamoDbBean entities which use a single table design for storing data.  
 * Each entity must implement the primaryId() method, and may override the sortKey method.
 * 
 * It assumes that all entities are uniquely identified by their simple class name.
 *  
 * @author Audacious Inquiry
 *
 */
@DynamoDbBean
public abstract class DynamoDbEntity {
	/**
	 * Compute the partition key for this object.
	 * The partition key is the simple type name of the object + the entity specific sort key.
	 * @return The partition key for this object.
	 */
	@DynamoDbPartitionKey
	@DynamoDbAttribute("entityType")
	public final String getEntityType() {
		return getClass().getSimpleName();
	}
	
	/**
	 * Compute the sort key for this object.
	 * The sort key is derived from the primary id for the object.
	 * @return The sort key for this object.
	 */
	@DynamoDbSortKey
	@DynamoDbAttribute("sortKey")
	public final String getSortKey() {
		return getPrimaryId();
	}
	
	/**
	 * Report the primary identifier for the object.
	 * This will be used as the sort key.
	 * @return The primary id for this object.
	 */
	public abstract String getPrimaryId();
	
	/**
	 * Phantom setter for entityType
	 * 
	 * The AWS DynamoDb SDK ignores bean properties that don't have both a reader 
	 * and a writer, failing to understand that some properties could be derived and therefore 
	 * NOT writable. Thus, this is ignored. This method exists simply to make DynamoDb work as expected
	 * for persistence.
	 * @param value The value to ignore
	 */
	public final void setEntityType(String value) {
		// Do nothing
	}
	
	/**
	 * Phantom setter for sortKey
	 * 
	 * The AWS DynamoDb SDK ignores bean properties that don't have both a reader 
	 * and a writer, failing to understand that some properties could be derived and therefore 
	 * NOT writable. Thus, this is ignored. This method exists simply to make DynamoDb work as expected
	 * for persistence.
	 * @param value The value to ignore
	 */
	public final void setSortKey(String value) {
		// Do nothing
	}
}
