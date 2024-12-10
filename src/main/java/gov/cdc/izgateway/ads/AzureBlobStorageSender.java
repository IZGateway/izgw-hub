package gov.cdc.izgateway.ads;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.xml.ws.http.HTTPException;

import jakarta.activation.DataHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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
            // If the blob already exists, we will overwrite it.
            con.setRequestMethod("PUT");
            
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

    @Override
    protected String getBlobType(Metadata meta) {
    	if (meta.getFileSize() > RestfulFileSender.BUFFERSIZE) {
    		// Use an AppendBlob for larger uploads to support
    		// retryable writes.
    		return "AppendBlob";
    	}
    	return "BlockBlob";
    }
    /**
     * Copy data stored in DataHandler to the URLConnection.
     * @return the status of the write.
     */
    @Override
    protected int writeData(HttpURLConnection con, IDestination route, DataHandler data, Metadata meta) throws IOException {
    	// OK, we have a connection, and we want to write data to it.
    	// If it fits in a single buffer, we just write it like normal.
		byte[] buffer = new byte[(int) Math.min(meta.getFileSize(), BUFFERSIZE)];

		// Only write on first block if entire blob can be written.
		int bytesToSend = meta.getFileSize() <= BUFFERSIZE ? (int)meta.getFileSize() : 0;
		
		int result = writeAsSingleBlock(con, route, data, meta, buffer, bytesToSend);
		
    	if (bytesToSend != 0) {
        	return result; 
    	} 
    	// If the initial write failed, throw an HTTPException so that 
    	// status will be reported.
    	if (result >= HttpStatus.BAD_REQUEST.value()) {
    		throw new HTTPException(result);
    	}
    	
    	return writeInMultipleBlocks(con.getURL(), route, data, meta, buffer);
    }
    
    protected int writeAsSingleBlock(HttpURLConnection con, IDestination route, DataHandler data, Metadata meta, byte[] buffer, int bytesToSend) throws IOException {
    	if (bytesToSend != 0) {
    		// Set uploaded property if we are finishing in a single block.
			con.addRequestProperty("x-ms-meta-uploaded", new Date().toString());
    	}
    	try (OutputStream os = con.getOutputStream();
            InputStream is = data.getInputStream()) {
    		int n = 0;
    		int percent = -1;	// Catch the first block
			try {
				if ((n = is.read(buffer, 0, bytesToSend)) > 0) {
					os.write(buffer, 0, n);
				}
			} catch (IOException ioex) {
				percent = -1;	// Reset to force logging at failure point
				throw ioex;
			} finally {
	            logProgress(route, meta, n, percent);
	            percent = (int) ((n * 100) / meta.getFileSize());
			}
        }
        return con.getResponseCode();
    }
	private void logProgress(IDestination route, Metadata meta, long count, int percent) {
    	// Only log after passing each percent threshold once 0% 1% 2% ... 99%
        if ((count * 100) / meta.getFileSize() > percent) {
        	log.info("ADS write to {} of {} ({}%) of {} bytes", route.getDestId(), 
        			count, (count * 100.0) / meta.getFileSize(), meta.getFileSize());
        }
	}
    
	/**
	 * This method appends any additional data beyond BUFFERSIZE to the Blob
	 * @param con	The original connection.
	 * @param route	The route we are sending to
	 * @param data	The data we are sending
	 * @param meta	The metadata
	 * @param buffer	The buffer to use
	 * @return
	 * @throws IOException
	 */
	protected int writeInMultipleBlocks(URL url, IDestination route, DataHandler data, Metadata meta, byte[] buffer) throws IOException {
    	// So here, the connection passed in has already been used, but no data has been written yet.
		long count = 0;
		
    	// We need to modify the URL, with slightly different parameters to append blocks.
    	url = new URL(url.toString().replace("?", "?comp=appendblock&"));
    	InputStream is = data.getInputStream();
    	int status = HttpStatus.CREATED.value();
		int percent = -1; // Also log first append block.
    	do {
    		int n = 0;
			if ((n = is.read(buffer)) <= 0) {
				// we read no data, what do we do here.
			}
			
			try {
				status = writeWithRetries(buffer, n, url, count, meta.getExtObjectKey());
				count += n;
			} finally {
	            logProgress(route, meta, count, percent);
	            percent = (int) ((count * 100) / meta.getFileSize());
			}
	    	if (status != HttpStatus.CREATED.value()) {
	    		throw new HTTPException(status);
	    	}
    	} while (count < meta.getFileSize());
    	
		return markComplete(url, meta);
    }

	/**
	 * Mark the blob as "Done"
	 * Adds metadata to the blob with the name "uploaded" and the value=date
	 * @param url	The request URL.
	 * @return	The status code.
	 * @throws IOException On error marking the blob complete
	 */
	@Retryable(retryFor=IOException.class, noRetryFor=HttpException.class, backoff= @Backoff(value=100, multiplier=2))
	private int markComplete(URL url, Metadata meta) throws IOException {
		String urlString = url.toString().replace("?comp=appendblock&", "?comp=metadata&");
		
		url = new URL(urlString);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("PUT");
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setFixedLengthStreamingMode(0);
		List<Header> headers = new ArrayList<>();
		addHeadersFromMetadata(meta, headers);
		headers.add(new BasicHeader("x-ms-meta-uploaded", new Date().toString()));
		for (Header h: headers) {
			con.addRequestProperty(h.getName(), h.getValue());
		}
		int result = con.getResponseCode();
		if (result != HttpStatus.OK.value()) {
			InputStream errorStream = con.getErrorStream();
			throw new HttpException(result, errorStream, null);
		}
		return result;
	}
	
	@Retryable(retryFor=IOException.class, noRetryFor=HttpException.class, backoff= @Backoff(value=100, multiplier=2))
	private int writeWithRetries(byte[] buffer, int numBytes, URL url, long count, String requestId) throws IOException {
		// Open a new connection for each block to be appended.
		//
		// This does two things:
		// 1. It enables each write operation to be retryable on an exception
		// 2. It escapes from firewall attempts to block sockets from sending more
		//    than N bytes of data, since each append is <= BUFFERSIZE.
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		
		con.setRequestMethod("PUT");
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setFixedLengthStreamingMode(numBytes);
		con.setRequestProperty("x-ms-client-request-id", requestId);
		con.setRequestProperty("x-ms-blob-condition-appendpos", Long.toString(count));
		con.getOutputStream().write(buffer, 0, numBytes);
		
		int result = con.getResponseCode();
		if (result != HttpStatus.CREATED.value()) {
			InputStream errorStream = con.getErrorStream();
			throw new HttpException(result, errorStream, null);
		}
		return result;
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
