package gov.cdc.izgateway.ads;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.xml.ws.http.HTTPException;

import jakarta.activation.DataHandler;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
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
	private static final int MAX_THREADS = 4;  // Use no more than 4 threads to upload
	private static final int CHUNKSIZE = 8*1024*1024; // Chunk in 8 Mb blocks an 8Gb upload will have 1024 chunks 
	/**
	 * Chunks are used to track memory regions to read and write.
	 * @author Audacious Inquiry
	 */
	private static record Chunk(byte[] buffer, int offset, int length) { // NOSONAR Array == is OK
		/** Break a chunk into a list of chunks each bufferSize in length
		 * This method allows chunks to be read in one size and written
		 * in another size.
		 * @param bufferSize	The size of the new chunks to create. 
		 */
		List<Chunk> chunks(int bufferSize) {
			List<Chunk> l = new ArrayList<>();
			for (int i = 0; i < length; i += bufferSize) {
				l.add(new Chunk(buffer, offset + i, Math.min(bufferSize, length - i)));
			}
			return l;
		}
	}
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
    		con.setRequestProperty("x-ms-blob-type", "BlockBlob");

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
    	try (OutputStream os = con.getOutputStream();
            InputStream is = data.getInputStream()) {
    		int n = 0;
			try {
				if ((n = is.read(buffer, 0, bytesToSend)) > 0) {
					os.write(buffer, 0, n);
				}
			} finally {
	            logProgress(route, meta, n);
			}
        }
        return con.getResponseCode();
    }
	private void logProgress(IDestination route, Metadata meta, long count) {
    	log.info("ADS write to {} of {} ({}%) of {} bytes", route.getDestId(), 
    			count, (count * 100.0) / meta.getFileSize(), meta.getFileSize());
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
    	InputStream is = data.getInputStream();
    	int status = HttpStatus.CREATED.value();
    	do {
    		int n = is.read(buffer);
			// if we read no data, what do we do here.
			
			try {
				Chunk chunk = new Chunk(buffer, 0, n);
				status = writeChunks(chunk, url, count, meta.getExtObjectKey());
				count += n;
			} finally {
	            logProgress(route, meta, count);
			}
	    	if (status != HttpStatus.CREATED.value()) {
	    		throw new HTTPException(status);
	    	}
    	} while (count < meta.getFileSize());
    	
    	int numBlockWritten = (int) ((count + CHUNKSIZE - 1) / CHUNKSIZE);
		return markComplete(url, meta, numBlockWritten);
    }

	/**
	 * This method enables a large block to be written in several parallel threads.
	 * 
	 * A single connection may only be able to handle a fraction of the available bandwidth 
	 * to the Azure endpoint, and so it would be better to run uploads of the large buffer in 
	 * smaller chunks in parallel. To optimize upload speed, we must determine the appropriate 
	 * chunking by tuning the number of connections used.
	 * 
	 * @param chunk	The (big) chunk to upload
	 * @param url	The url of the blob where it is to be uploaded
	 * @param count	The number of bytes already written
	 * @param requestId	The request identifier
	 * @return	The status of the writes (either all CREATED or status of first failed block)
	 * @throws IOException	If an error occurs
	 */
	private int writeChunks(Chunk chunk, URL url, long count, String requestId) throws IOException {
		List<Chunk> chunkList = chunk.chunks(CHUNKSIZE);
		List<Callable<Integer>> taskList = new ArrayList<>();
		int blockId = (int) (count / CHUNKSIZE);
		// Write in multiple threads.
		for (int i = 0; i < chunkList.size(); i++) {
			Chunk cnk = chunkList.get(i);
			int theBlock = blockId + i;
			long theCount = count;
			taskList.add(() -> 
				writeWithRetries(
					cnk,
					url,
					theBlock,
					theCount,
					requestId
				)
			);
			count += cnk.length;
		}
		int status = 0;
		ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
		try {
			List<Future<Integer>> futures = executor.invokeAll(taskList);
			for (Future<Integer> result: futures) {
				status = result.get();
		    	if (status != HttpStatus.CREATED.value()) {
		    		throw new HTTPException(status);
		    	}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Upload interrupted", e);  // NOSONAR
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException ioex) {
				throw ioex;
			}
			if (cause instanceof RuntimeException rex) {
				throw rex;
			}
			throw new RuntimeException(e);  // NOSONAR Should never get here, but if we do we throw
		} finally {
			executor.shutdownNow();
		}
		return status;
	}
	
	private boolean canCompleteInOneCall(int n) {
		return n < CHUNKSIZE || MAX_THREADS <= 1;
	}
	/**
	 * Write a chunk to the blob
	 * @param chunk	The chunk to write
	 * @param url	The base url for accessing the blob.
	 * @param blockId	The block being written
	 * @param count	The number of bytes already written.
	 * @param requestId	The request identifier
	 * @return	The status of the write
	 * @throws IOException	If an unrecoverable IO Exception occurs
	 */
	@Retryable(retryFor=IOException.class, noRetryFor=HttpException.class, backoff= @Backoff(value=100, multiplier=2))
	private int writeWithRetries(Chunk chunk, URL url, int blockId, long count, String requestId) throws IOException {
		String blockIdString = encodeBlockId(blockId);

		// Open a new connection for each block to be appended.
		//
		// This does two things:
		// 1. It enables each write operation to be retryable on an exception
		// 2. It escapes from firewall attempts to block sockets from sending more
		//    than N bytes of data, since each append is <= BUFFERSIZE.
		url = new URL(
			url.toString().replace("?", 
				"?comp=block&blockid=" 
				+ URLEncoder.encode(blockIdString, StandardCharsets.UTF_8) 
				+ "&"
			)
		);
		System.err.printf("%6d %s %s%n", blockId, blockIdString, chunk.length());
		HttpURLConnection con = getBlobConnection(url, requestId);
		con.setFixedLengthStreamingMode(chunk.length());
		con.getOutputStream().write(chunk.buffer(), chunk.offset(), chunk.length());
		
		int result = con.getResponseCode();
		if (result != HttpStatus.CREATED.value()) {
			InputStream errorStream = con.getErrorStream();
			throw new HttpException(result, errorStream, null);
		}
		return result;
	}
	
	/**
	 * Get a connection to a blob
	 * @param url	The URL for the connection
	 * @param requestId	The request identifier
	 * @return	An HttpURLConnection set up to call an Azure Blop API
	 * @throws IOException	If the connection cannot be opened
	 */
	private HttpURLConnection getBlobConnection(URL url, String requestId) throws IOException {
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("PUT");
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setRequestProperty("x-ms-client-request-id", requestId);
		return con;
	}

	/**
	 * Base-65 encode a block identifier
	 * @param blockId	The block number, a 24-bit integer
	 * @return	The base-64 encoded block identifier (a four character string).
	 */
	private String encodeBlockId(int blockId) {
		byte[] data = { (byte)((blockId >> 16) & 0xFF), (byte)((blockId >> 8) & 0xFF), (byte)(blockId & 0xFF) };
		return Base64.encodeBase64String(data);
	}

	/**
	 * Mark the blob as "Done"
	 * Adds metadata to the blob with the name "uploaded" and the value=date
	 * @param url	The request URL.
	 * @return	The status code.
	 * @throws IOException On error marking the blob complete
	 */
	@Retryable(retryFor=IOException.class, noRetryFor=HttpException.class, backoff= @Backoff(value=100, multiplier=2))
	private int markComplete(URL url, Metadata meta, int numBlocks) throws IOException {
		// Update the URL
		String urlString = url.toString().replace("?", "?comp=blocklist&"); 
		
		url = new URL(urlString);
		HttpURLConnection con = getBlobConnection(url, meta.getExtObjectKey());

		// Compute the payload
		String blockList = getBlockList(numBlocks);
		System.err.println(blockList);
		byte[] data = blockList.getBytes(StandardCharsets.UTF_8);

		// Write the data
		con.setFixedLengthStreamingMode(data.length);
		con.getOutputStream().write(data);
		
		// Check the response
		int result = con.getResponseCode();
		if (result != HttpStatus.CREATED.value()) {
			InputStream errorStream = con.getErrorStream();
			throw new HttpException(result, errorStream, null);
		}
		return result;
	}
	
	private String getBlockList(int numBlocks) {
		StringBuilder b = new StringBuilder();
		b.append("<?xml version='1.0' encoding='utf-8'?>\n");  
		b.append("<BlockList>\n");
		for (int blockNum = 0; blockNum < numBlocks; blockNum++) {
			b.append(" <Latest>");
			b.append(encodeBlockId(blockNum));
			b.append("</Latest>\n");
		}
		b.append("</BlockList>\n");
		return b.toString();
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
