package gov.cdc.izgateway.db.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.MessageHeader;
import gov.cdc.izgateway.repository.IMessageHeaderRepository;

import java.util.List;

@Repository
public interface MessageHeaderRepository extends JpaRepository<MessageHeader, String>, IMessageHeaderRepository {
}

