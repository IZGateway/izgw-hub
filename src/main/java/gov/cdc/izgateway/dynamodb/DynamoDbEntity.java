package gov.cdc.izgateway.dynamodb;

import lombok.Getter;
import lombok.Setter;
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
	public final String entityType() {
		return getClass().getSimpleName();
	}
	
	/**
	 * Compute the sort key for this object.
	 * The sort key is the primary id for the object.
	 * @return The sort key for this object.
	 */
	@DynamoDbSortKey
	public abstract String primaryId();
}
