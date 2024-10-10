package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.repository.IMessageHeaderRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for MessageHeaders.
 * 
 * @author Audacious Inquiry
 */
@Repository
public class MessageHeaderRepository extends DynamoDbRepository<MessageHeader> implements IMessageHeaderRepository {
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public MessageHeaderRepository(@Autowired DynamoDbEnhancedClient client) {
		super(MessageHeader.class, client);
	}
	
	@Override
	public MessageHeader saveAndFlush(MessageHeader entity) {
		return super.saveAndFlush(entity);
	}

	@Override
	public IMessageHeader saveAndFlush(IMessageHeader h) {
		if (h instanceof MessageHeader header) {
			return saveAndFlush(header);
		}
		return saveAndFlush(new MessageHeader(h));
	}
}
