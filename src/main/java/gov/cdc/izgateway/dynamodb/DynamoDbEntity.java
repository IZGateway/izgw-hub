package gov.cdc.izgateway.dynamodb;

import org.apache.commons.lang3.StringUtils;

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
	 * Implement this method to return the primary id of the entity.
	 * @return	The primary key of the entity.
	 */
	public String primaryId() {
		return getClass().getSimpleName();
	}
	
	/**
	 * Override this method to return the sort key of the entity.
	 * @return	The sort key of the entity.
	 */
	public String sortKey() {
		return null;
	}
	
	/**
	 * Compute the partition key for this object.
	 * The partition key is the simple type name of the object + the entity specific sort key.
	 * @return The partition key for this object.
	 */
	@DynamoDbPartitionKey
	public final String dynamoDbPartitionKey() {
		return StringUtils.joinWith("#", getClass().getSimpleName(), primaryId());
	}
	
	/**
	 * Compute the sort key for this object.
	 * The sort key is the simple type name of the object + the entity specific sort key.
	 * @return The sort key for this object.
	 */
	@DynamoDbSortKey
	public final String dynamoDbSortKey() {
		String sortKey = sortKey();
		if (sortKey == null) {
			return dynamoDbPartitionKey();
		}
		return StringUtils.joinWith("#", getClass().getSimpleName(), sortKey);
	}
}
