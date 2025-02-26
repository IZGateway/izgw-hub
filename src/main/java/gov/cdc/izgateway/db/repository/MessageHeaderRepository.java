package gov.cdc.izgateway.db.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.MessageHeader;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.model.IMessageHeader;

/**
 * Message Header Repository stores information about which MSH values map to individual use cases
 * (e.g., IIS to IIS, Provider to IIS).
 * 
 * @author Audacious Inquiry
 */
@Repository
public interface MessageHeaderRepository extends JpaRepository<MessageHeader, String>, IMessageHeaderRepository {

	@Override
	default IMessageHeader store(IMessageHeader h) {
		if (h instanceof MessageHeader mh) {
			return saveAndFlush(mh);
		}
		return saveAndFlush(new MessageHeader(h));
	}
}

