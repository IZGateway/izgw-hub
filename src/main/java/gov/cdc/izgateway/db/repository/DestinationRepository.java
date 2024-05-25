package gov.cdc.izgateway.db.repository;



import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.Destination;
import gov.cdc.izgateway.db.model.Destination.DestinationId;

@Repository
public interface DestinationRepository extends JpaRepository<Destination, DestinationId>{
    }