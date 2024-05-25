package gov.cdc.izgateway.ads;


import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.soap.fault.*;

import org.apache.commons.lang3.tuple.Pair;

import jakarta.activation.DataHandler;



import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

/**
 * Contract for Blob Storage sender interface to enable reuse of this
 * with other blob stores (e.g., Azure, S3 bucket).
 */
public interface FileSender {
    HttpURLConnection sendFile(IDestination route, DataHandler dataHandler, Metadata meta) throws MessageTooLargeFault, HubClientFault, DestinationConnectionFault, MetadataFault, SecurityFault;
    String getStatus(IDestination r) throws HubClientFault, MetadataFault, DestinationConnectionFault, SecurityFault;
    Pair<InputStream, Map<String, List<String>>> getFile(IDestination r, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault;
    String deleteFile(IDestination route, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault;
	String getSubmissionStatus(IDestination r, Metadata m) throws DestinationConnectionFault, MetadataFault, HubClientFault;
}
