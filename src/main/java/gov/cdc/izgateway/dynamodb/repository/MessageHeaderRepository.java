package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.model.IMessageHeader;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for MessageHeaders.
 * 
 * @author Audacious Inquiry
 */
public class MessageHeaderRepository extends DynamoDbRepository<MessageHeader> implements IMessageHeaderRepository {
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public MessageHeaderRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(MessageHeader.class, client, tableName);
	}
	
	@Override
	public IMessageHeader store(IMessageHeader h) {
		if (h instanceof MessageHeader header) {
			return saveAndFlush(header);
		}
		return saveAndFlush(new MessageHeader(h));
	}
	
	@Override
	public void deleteById(String primaryId) {
		super.delete(primaryId);
	}
}
