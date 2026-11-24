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
    /**
     * Sends a file to the destination.
     * @param route the destination
     * @param dataHandler the file data
     * @param meta the metadata of the file
     * @return the connection that sent the file
     * @throws MessageTooLargeFault if the file is larger than the maximum upload size
     * @throws HubClientFault if the destination returns an error or the request is not valid
     * @throws DestinationConnectionFault if the hub cannot connect to the destination
     * @throws MetadataFault if the metadata is not valid
     * @throws SecurityFault if a security error occurs
     */
    HttpURLConnection sendFile(IDestination route, DataHandler dataHandler, Metadata meta) throws MessageTooLargeFault, HubClientFault, DestinationConnectionFault, MetadataFault, SecurityFault;
    /**
     * Checks the connection to the destination.
     * @param r the destination
     * @return the URI of the destination
     * @throws HubClientFault if the destination returns an error or the request is not valid
     * @throws MetadataFault if the metadata is not valid
     * @throws DestinationConnectionFault if the hub cannot connect to the destination
     * @throws SecurityFault if a security error occurs
     */
    String getStatus(IDestination r) throws HubClientFault, MetadataFault, DestinationConnectionFault, SecurityFault;
    /**
     * Gets a file from the destination.
     * @param r the destination
     * @param meta the metadata that identifies the file
     * @return the file content and the HTTP response headers
     * @throws MetadataFault if the metadata is not valid
     * @throws HubClientFault if the destination returns an error or the request is not valid
     * @throws DestinationConnectionFault if the hub cannot connect to the destination
     * @throws SecurityFault if a security error occurs
     */
    Pair<InputStream, Map<String, List<String>>> getFile(IDestination r, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault;
    /**
     * Deletes a file at the destination.
     * @param route the destination
     * @param meta the metadata that identifies the file
     * @return the result of the delete operation
     * @throws MetadataFault if the metadata is not valid
     * @throws HubClientFault if the destination returns an error or the request is not valid
     * @throws DestinationConnectionFault if the hub cannot connect to the destination
     * @throws SecurityFault if a security error occurs
     */
    String deleteFile(IDestination route, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault;
	/**
	 * Gets the status of a file submission from the destination.
	 * @param r the destination
	 * @param m the metadata that identifies the submission
	 * @return the submission status
	 * @throws DestinationConnectionFault if the hub cannot connect to the destination
	 * @throws MetadataFault if the metadata is not valid
	 * @throws HubClientFault if the destination returns an error or the request is not valid
	 * @throws SecurityFault if a security error occurs
	 */
	String getSubmissionStatus(IDestination r, Metadata m) throws DestinationConnectionFault, MetadataFault, HubClientFault, SecurityFault;
}
