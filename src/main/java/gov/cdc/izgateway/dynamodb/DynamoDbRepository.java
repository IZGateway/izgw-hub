package gov.cdc.izgateway.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;

import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.DynamoDbEntity;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.BeanTableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * A DynamoDbRepository for a given type of database entity.
 * @author Audacious Inquiry
 *
 * @param <T>
 */
@Slf4j
public class DynamoDbRepository<T extends DynamoDbEntity> {
	/**
	 * The table name for the repository of entities.  Because we are using a single table 
	 * design, there is only one table name.
	 */
	public static final String TABLE_NAME = "izgw-hub";
	private final Class<T> entityClass;
	private final DynamoDbTable<T> table;
	
	/**
	 * Create a new DynamoDbRepository for a given entity type.
	 * @param entityClass	The class representing the entity to create the repository for.
	 * @param client	The DynamoDbEnhancedClient to use to create the repository.
	 */
	public DynamoDbRepository(Class<T> entityClass, DynamoDbEnhancedClient client) {
		this.entityClass = entityClass;
		// This creates the schema from the class, but does not get subclass attributes.
		BeanTableSchema<T> schema = TableSchema.fromBean(entityClass);
		table = client.table(TABLE_NAME, schema);
		log.info("Table initialized for {}", entityClass.getSimpleName());
	}
	
	/**
	 * Find the specified entity by its primary identifier
	 * @param primaryId	The primary identifier
	 * @return	The found entity.
	 */
	public T find(String primaryId) {
		Key key = Key.builder().partitionValue(entityClass.getSimpleName()).sortValue(primaryId).build();
		Iterator<T> i = table.query(QueryConditional.keyEqualTo(key)).items().iterator();
		if (!i.hasNext()) {
			return null;
		}
		return i.next();
	}
	
	/**
	 * This is a protected method that supports finding by a partial sort key.
	 * It is intended to be called by methods which are better named in the 
	 * extending interface to collect entities with particular properties.
	 * 
	 * @param partialKey	The partial key
	 * @return	The found entities.
	 */
	protected List<T> findByType(String partialKey) {
		Key key = Key.builder().partitionValue(entityClass.getSimpleName()).sortValue(partialKey).build();
		return table.query(QueryConditional.sortBeginsWith(key)).items().stream().toList();
	}
	
	/**
	 * Find all items.
	 * @return	All entities of the specified type stored in the database 
	 */
	public List<T> findAll() {
		Key key = Key.builder().partitionValue(entityClass.getSimpleName()).build();
		return table.query(QueryConditional.keyEqualTo(key)).items().stream().toList();
	}
	
	/**
	 * Create a new entity of the specified type.
	 * @return The new entity.
	 */
	public T createEntity() {
		try {
			return entityClass.getDeclaredConstructor().newInstance();
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e) {
			String msg = "Unexpected exception creating " + entityClass.getName();
			log.error(Markers2.append(e), msg);
			throw new ServiceConfigurationError(msg, e);
		}
	}
	
	/**
	 * Delete the specified entity.  Override this method to make it public in derived classes. 
	 * @param primaryId	The primary key of the entity to delete.
	 */
	protected void delete(String primaryId) {
		if (primaryId == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		Key key = Key.builder().partitionValue(entityClass.getSimpleName()).sortValue(primaryId).build();
		table.deleteItem(key);
	}
	
	/**
	 * Create or update the specified entity.  Override this method to make it public in derived classes.  
	 * @param entity	The entity to create.
	 * @return	The saved entity.
	 */
	protected T saveAndFlush(T entity) {
		if (entity == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		table.putItem(entity);
		return entity;
	}
	
	protected T saveIfNotExists(T entity) {
		Expression ex = Expression.builder().expression("attribute_not_exists(" + DynamoDbEntity.ENTITY_TYPE + ")").build();
		PutItemEnhancedRequest<T> request = PutItemEnhancedRequest.builder(entityClass).item(entity).conditionExpression(ex).build();
		try {
			table.putItem(request);
			return entity;
		} catch (ConditionalCheckFailedException e) {
			return null;
		} catch (Exception e) {
			log.warn(Markers2.append(e), "Conditional Create on {}:{} failed", entity.getEntityType(), entity.getSortKey());
			return null;
		}
	}
}
