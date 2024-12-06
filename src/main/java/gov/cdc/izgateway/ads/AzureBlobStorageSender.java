package gov.cdc.izgateway.ads;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import jakarta.activation.DataHandler;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.springframework.stereotype.Component;

import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.ClientTlsSupport;
import gov.cdc.izgateway.soap.fault.DestinationConnectionFault;
import gov.cdc.izgateway.soap.fault.MessageTooLargeFault;
import gov.cdc.izgateway.soap.fault.MessageTooLargeFault.Direction;

/**
 * This class implements the FileSender interface to Azure and the Azurite Azure emulator.
 */
@Component
@Slf4j
public class AzureBlobStorageSender extends RestfulFileSender implements FileSender {
    static final int  BUFFERSIZE = 1048576; //1MB
    /**
     * Create a new storage sender to create an AzureBlob
     * @param config	The configuration for the sender
     * @param tlsSupport	ClienTlsSupport object to make the connection
     */
    public AzureBlobStorageSender(SenderConfig config, ClientTlsSupport tlsSupport) {
		super(config, tlsSupport);
	}
    @Override
    protected HttpURLConnection getConnection(String type, IDestination route, Metadata meta, DataHandler data) throws IOException, MetadataFault, DestinationConnectionFault, URISyntaxException {
        HttpURLConnection con = null;
        URL base;
        if (meta != null) {
            base = getUrl(meta, route);
            // Remove any query parameters
            meta.setPath(StringUtils.substringBefore(base.toString(), "?"));
        } else {
            base = new URL(route.getDestUri());        
        }

        URL queryUrl;
        switch (type) {
        case "DELETE":
            con = getConnection(base);
            con.setRequestMethod("DELETE");
            // Set the headers from the metadata provided.
            
            break;
        case "LIST":
            queryUrl = new URL(base + "?" + route.getPassword() );
            con = getConnection(queryUrl);
            // This is a GET, there is nothing else to write
            con.setDoOutput(false);
            con.setRequestMethod("GET");
            break;
        case "PING":
            queryUrl = new URL(base + "?restype=container&comp=list&maxresults=1&" + route.getPassword() );
            con = getConnection(queryUrl);
            // This is a GET, there is nothing else to write
            con.setDoOutput(false);
            con.setRequestMethod("GET");
            break;
        case "GET":
            con = getConnection(base);
            con.setDoOutput(false);
            con.setRequestMethod("GET");
            break;
            
        case "POST":
            con = getConnection(base);
            // We use PUT, so must be able to write to the connection.
            con.setDoOutput(true);

            // Azure uses PUT for it's CREATE/WRITE API Calls
            con.setRequestMethod("PUT");
            
            // If the blob already exists, overwrite it.
            con.setChunkedStreamingMode(BUFFERSIZE);
            //con.setFixedLengthStreamingMode(meta.getFileSize());  // NOSONAR: meta won't be null here

            break;

        case "STATUS":
        default:
            return null;
        }
        
        // Set the headers from the metadata provided.
        if (meta != null) {
            for (Header h: getHeaders(meta, data)) {
                con.addRequestProperty(h.getName(), h.getValue());
            }
        }
        // We always want to read the response.
        con.setDoInput(true);
        return con;
    }

    /**
     * Copy data stored in DataHandler to the URLConnection.
     * @return 
     */
    @Override
    protected int writeData(HttpURLConnection con, IDestination route, DataHandler data, Metadata meta) throws IOException {
        try (OutputStream os = con.getOutputStream();
            InputStream is = data.getInputStream()) {
            byte[] buffer = new byte[BUFFERSIZE];
            long count = 0;
            int n;
            while ((n = is.read(buffer)) > 0) {
                os.write(buffer, 0, n);
                count += n;
                log.info("ADS write to {} of {} ({}%) of {} bytes", route.getDestId(), 
                		count, (count * 100.0) / meta.getFileSize(), meta.getFileSize());
            }
        }
        return con.getResponseCode();
    }

	@Override
	protected void setExpiration(HttpURLConnection con, Date date) {
		con.setRequestProperty("x-ms-expiry-option", "RelativeTonow");
		con.setRequestProperty("x-ms-expiry-time", Long.toString(config.getMaxAgeInMinutes() * 60000l));  
	}

	private static int blobSizeComparator(AzureBlob b1, AzureBlob b2) {
		long sign = b1.getLength() - b2.getLength();
		if (sign < 0) {
			return -1;
		}
		return sign > 0 ? 1 : 0;
	}
	
	@Override
	protected void checkForSpace(HttpURLConnection con, long fileSize) throws MessageTooLargeFault {
		long diskFreeSpace = getDiskFreeSpace();
		if (fileSize + getMaxUploadSize() <= diskFreeSpace) {
			// There's enough room
			return;
		}
		
		AzureBlob[] b = AzureBlob.listBlobs(con, getSslSocketFactory());
		Arrays.sort(b, AzureBlobStorageSender::blobSizeComparator);
		for (AzureBlob nextToDelete: b) {
			nextToDelete.delete(con, getSslSocketFactory());
			if (fileSize + getMaxUploadSize() < getDiskFreeSpace()) {
				// There's enough room
				return;
			}
		}
		
		// Still not enough room for this upload and the next, see if there is room for this one
		long freeDiskSpace = getDiskFreeSpace(); 
		if (fileSize < freeDiskSpace) {
			log.warn("Running low on storage: {} free", freeDiskSpace);
			return;
		}
		
		// Not enough room
		log.error("Out of storage for upload: {} free", freeDiskSpace);
		throw new MessageTooLargeFault(Direction.REQUEST, freeDiskSpace, fileSize);
	}

	@Override
	protected String getSubmissionStatus(HttpURLConnection con) {
		throw new UnsupportedOperationException();
	}
}
