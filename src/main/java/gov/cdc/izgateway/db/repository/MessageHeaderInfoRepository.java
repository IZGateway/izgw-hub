package gov.cdc.izgateway.db.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.MessageHeader;

import java.util.List;

@Repository
public interface MessageHeaderInfoRepository extends JpaRepository<MessageHeader, String> {
    List<MessageHeader> findByMshIn(List<String> msh);

}

