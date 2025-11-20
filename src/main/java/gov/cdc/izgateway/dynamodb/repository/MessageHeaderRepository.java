package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for MessageHeaders.
 * 
 * @author Audacious Inquiry
 */
public class MessageHeaderRepository extends DynamoDbRepository<MessageHeader> implements IMessageHeaderRepository<MessageHeader> {
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table to use
	 */
	public MessageHeaderRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(MessageHeader.class, client, tableName);
	}
	
	@Override
	public MessageHeader store(MessageHeader h) {
		return saveAndFlush(h);
	}

	@Override
	public void deleteById(String id) {
		super.delete(id);
	}
}
