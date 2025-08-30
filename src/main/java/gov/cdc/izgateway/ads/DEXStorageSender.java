package gov.cdc.izgateway.ads;

import com.fasterxml.jackson.annotation.JsonProperty;

import gov.cdc.izgateway.ads.mockdex.DexConfiguration;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.ClientTlsSupport;
import gov.cdc.izgateway.security.oauth.ExternalTokenStore;
import gov.cdc.izgateway.soap.fault.DestinationConnectionFault;
import gov.cdc.izgateway.soap.fault.MessageTooLargeFault;
import gov.cdc.izgateway.utils.CapturingSSLSocketFactory;
import io.tus.java.client.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DateFormatUtils;


import org.apache.http.Header;
import org.apache.http.client.HttpResponseException;
import org.bouncycastle.jsse.util.SNISocketFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.activation.DataHandler;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * This class implements the FileSender interface to Azure and the Azurite Azure emulator.
 */
@Slf4j
@Component
public class DEXStorageSender extends RestfulFileSender implements FileSender {
    private static final Random RANDOM = new Random();
    @Getter
    @Setter
    private static int currentChunkSize = 2 << 20;		// 1Mb
    @Getter
    @Setter
	private static int maxChunkSize = 10 * (2 << 20);   // 10MB : NOTE: We'd like to go to 128Mb, but DataHandler somehow limits read to 10Mb
    private final class DexTusExecutor extends TusExecutor {
		private static final String FINGERPRINT = "fingerprint";
		private static final String METADATA = "metadata";
		private final DexTusClient client;
		private final TusUpload upload;
		private final Metadata meta; 
		private static final int MAX_RETRIES = 4;
		private final long startTime = System.currentTimeMillis();

		private DexTusExecutor(IDestination route, HttpURLConnection con, DataHandler data, Metadata meta) throws IOException, MetadataFault {
			this.client = new DexTusClient(route, con);
			this.meta = meta;
			this.upload = getUploader(data);
		}
		
	    private TusUpload getUploader(DataHandler data) throws IOException, MetadataFault {
	        TusUpload u = new TusUpload();
	        u.setMetadata(getMetadataAsMap(meta));
			Map<String, String> metadataAsMap = getMetadataAsMap(meta);
			metadataAsMap.put("filename", metadataAsMap.get("meta_ext_filename"));
	        u.setInputStream(data.getInputStream());
	        u.setSize(meta.getFileSize());
	        u.setFingerprint(getFingerprint());
	        return u;
	    }
	    
	    private String getFingerprint() {
	        byte[] fingerprint = new byte[16];
	        RANDOM.nextBytes(fingerprint);
	        return Base64.getEncoder().encodeToString(fingerprint);
	    }
	    
	    private int updateChunkSize(TusUploader uploader, int chunkSize) {
        	try {
        		uploader.setChunkSize(chunkSize);
        		return Math.min(chunkSize + chunkSize, maxChunkSize);
        	} catch (OutOfMemoryError omerr) {
        		// If we could not get the memory, don't cause a failure. The original buffer should still be good.
        	}
    		return uploader.getChunkSize();
	    }

		@Override
		protected void makeAttempt() throws ProtocolException, IOException {
			int retries = 0;
			boolean success = false;
			ProtocolException lastPex = null;
			int chunkSize = currentChunkSize;
			while (!success && retries < MAX_RETRIES) {
		        try {
		            // First try to resume an upload. If that's not possible we will create a new
		            // upload and get a TusUploader in return. This class is responsible for opening
		            // a connection to the remote server and doing the uploading.
		            TusUploader uploader = client.resumeOrCreateUpload(upload);
   
		            // Upload the file in chunks.
	   	            do {
		            	chunkSize = updateChunkSize(uploader, chunkSize);
						reportProgress(uploader);
						
			            // Upload the file as long as data is available. Once the
			            // file has been fully uploaded the method will return -1
		            } while (uploader.uploadChunk() > -1);
		            meta.setPath(uploader.getUploadURL().getPath());
		            // Allow the HTTP connection to be closed and cleaned up
		            uploader.finish();
		            success = true;
		        } catch (ProtocolException pex ) {  // NOSONAR: Logging and throwing OK
  		        	++retries;
  		        	chunkSize = currentChunkSize;	// Reset chunk size on failure.
		    		String error = getError(pex);
		    		lastPex = pex;
  		            log.error(Markers2.append(METADATA, meta, FINGERPRINT, upload.getFingerprint()), 
  		                "Exception #{} while uploading to {}: {}{}", retries, upload.getFingerprint(), pex.getMessage(), error, pex);
		            // On Unauthorized, try again with a refreshed token.  For other exceptions,
		            // defer to makeAttempts to perform exponential back-off.
		            if (pex.getCausingConnection().getResponseCode() == HttpServletResponse.SC_UNAUTHORIZED) {
		            	continue;
		            }
  	            	ProtocolException ex = new ProtocolException(error);
  	            	ex.initCause(pex);
  	            	throw ex;
		        } catch (IOException | RuntimeException ex) { // NOSONAR: Logging and throwing OK
		            // Log it and defer to makeAttempts to perform exponential back-off.
		            log.error(Markers2.append(ex, METADATA, meta, FINGERPRINT, upload.getFingerprint()), 
		                    "RuntimeException while uploading to {}: {}", upload.getFingerprint(), ex.getMessage());
		            throw ex;
		        }
			}
			if (!success) {
				throw lastPex;
			}
		}

		private void reportProgress(TusUploader uploader) {
			// Calculate the progress using the total size of the uploading file and
			// the current offset.
			if (dexConfig.isReportingProgressEnabled()) {
			    long totalBytes = upload.getSize();
			    long bytesUploaded = uploader.getOffset(); 
			    long remaining = totalBytes - bytesUploaded;
			    double progress = (double) bytesUploaded / totalBytes * 100;
			    long timeUsed = Math.max(System.currentTimeMillis() - startTime, 1);
			    long estimatedRemaining = (timeUsed * remaining)/ Math.max(bytesUploaded, 1) / 1000;
			    long mbpsRate = (8 * bytesUploaded / (1024 * 1024) / timeUsed) / 1000;
			    log.info(
			        Markers2.append(
			        		"progress", progress,
			        		"bytesUploaded", bytesUploaded,
			        		"remaining", remaining,
			        		"timeRemaining", estimatedRemaining,
			        		"rate", mbpsRate,
			        		"totaBytes", totalBytes,
			        		"url", client.getUploadCreationURL(),
			        		FINGERPRINT, upload.getFingerprint()), 
			        "{} progress {} (estimated {} b / {} s remaining @ {} mbps)", upload.getFingerprint(), progress, remaining, estimatedRemaining, mbpsRate);
			}
		}

		private String getError(ProtocolException pex) {
			String error = "";
			InputStream is = pex.getCausingConnection().getErrorStream();
			if (is != null) {
				try {
					error += IOUtils.toString(is, StandardCharsets.UTF_8);
				} catch (IOException ex) {
					// Swallow it.
				}
			}
			return error;
		}
	}
    
	private final class DexTusClient extends TusClient {
		private final IDestination route;
		private String token = null;

		private DexTusClient(IDestination route, HttpURLConnection con) {
			this.route = route;
			
			// Configure tus HTTP endpoint. This URL will be used for creating new uploads
	        // using the Creation extension
			// It needs to use a URL Stream Handler to enable SNI connections on upload requests.
			URL url = con.getURL();
	        setUploadCreationURL(url);
	        // Enable resumable uploads by storing the upload URL in memory
	        enableResuming(new TusURLMemoryStore());
		}
		
		private void setToken(String token) {
			this.token = token;
		}

		@Override 
		public void prepareConnection(@NotNull HttpURLConnection connection) {
		    try {
	    		setToken(getToken(route));
		    } catch (IOException e) {
		        // Log the error here.  The missing token will cause exceptions later
		        // that will also be caught and logged.  No need to throw and since
		        // this is over-ride of external API, we cannot change it.
		        log.error(Markers2.append(e), "Cannot get token: {}", e.getMessage());
		    }
		    
		    super.prepareConnection(connection);
		    enableSNI(connection);
		    
		    if (token != null) {
		    	connection.addRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		    }
		    switch (connection.getRequestMethod()) {
		    case "HEAD", "OPTIONS", "GET", "DELETE":
		    	// These methods have no content, for force Content-Length to 0.
		    	connection.addRequestProperty("Content-Length", "0");
		    	try {
		    		if (connection.getDoOutput()) {
		    			// If opened for writing, get the OutputStream so Content-Length is written
		    			// This is a work-around for DEX where Content-Length or Chunked encoding headers
		    			// are required on all inputs, possibly due to Firewall or other intermediary.
		    			connection.getOutputStream();
		    		}
				} catch (IOException e) {
					// Swallow this exception, if something is really
					// wrong, it will error later.
				}
		    	break;
		    case "POST", "PUT", "PATCH": // NOSONAR: Fallthrough OK
		    	connection.setDoOutput(true);  
		    	// falling through
		    default:
		        connection.setChunkedStreamingMode(currentChunkSize);
		    }
		}

		private void enableSNI(@NotNull HttpURLConnection connection) {
		    if (connection instanceof HttpsURLConnection con) {
			    SSLSocketFactory delegate;
		    	if (fiddle ) {
			    	delegate = new CapturingSSLSocketFactory(con.getSSLSocketFactory());
		    	} else {
		    		delegate = getSslSocketFactory();
		    	}
		    	// Ensure the connection uses SNI
		    	con.setSSLSocketFactory(new SNISocketFactory(delegate, con.getURL()));
		    }
		}
	}

    /** A map from OAuthEnd point to the current token for that endpoint */

    /** The set of fields agreed to according to the specification */
    private final DexConfiguration dexConfig;
    
    /**
     * Create a storage sender for the DEX Endpoint
     * @param config	The Sender configuration
     * @param tlsSupport	TLS Support for client connections
     * @param dexConfig	The DEX Configuration
     */
    public DEXStorageSender(SenderConfig config, final ClientTlsSupport tlsSupport, final DexConfiguration dexConfig) {
    	super(config, tlsSupport);
    	this.dexConfig = dexConfig;
    }

	@Override
    public HttpURLConnection getConnection(String type, IDestination route, Metadata meta, DataHandler data) throws IOException, DestinationConnectionFault, MetadataFault {
        HttpURLConnection con = null;
        // Base for status request
        URL base = null;
        
        base = new URL(route.getDestUri());
        String token = null;
        if (meta != null) {
            token = getToken(route);
        } 
        
        switch (type) {
        case "DELETE":
            con = getConnection(base);
            con.setRequestMethod(type);
            // Set the headers from the metadata provided.
            
            break;
        case "STATUS":
        	String path = meta.getPath();
        	if (path.startsWith("/")) {
        		path = path.substring(1);
        	}
        	base = new URL(base, "upload/info/" + path);
        	con = getConnection(base);
        	con.setRequestMethod("GET");
        	break;
        case "PING":
            token = getToken(route);
            con = getConnection(base);
            // This is an OPTIONS, there is nothing else to write
            con.setDoOutput(false);
            con.setRequestMethod("OPTIONS");
            break;
            
        case "GET":
            con = getConnection(base);
            con.setDoOutput(false);
            con.setRequestMethod(type);
            break;
            
        case "POST":
            con = getConnection(base);
            con.setDoOutput(true);
            con.setRequestMethod(type);
            break;

        default:
            return null;
        }
        
        // Set the headers from the metadata provided.
        if (meta != null && !"STATUS".equals(type)) {
            for (Header h: getHeaders(meta, data)) {
                con.addRequestProperty(h.getName(), h.getValue());
            }
        }
        // Add token property if non-null
        if (token != null) {
            con.addRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        
        // We always want to read the response.
        con.setDoInput(true);
        
        // Write output in CHUNK_SIZE (1Mb) Chunks
        if (con.getDoOutput()) {
        	con.setChunkedStreamingMode(currentChunkSize);
        }
        
        return con;
    }

    /**
     * Copy data stored in DataHandler to the URLConnection.
     * @return  The HTTP Status (201 Created) on success.
     */
    @Override
    public int writeData(HttpURLConnection con, IDestination route, DataHandler data, Metadata meta) throws IOException, DestinationConnectionFault, MetadataFault, ProtocolException {
        // We wrap our uploading code in the TusExecutor class which will automatically
        // catch
        // exceptions and issue retries with small delays between them and take fully
        // advantage of tus' resumability to offer more reliability.
        // This step is optional but highly recommended.
        TusExecutor executor = new DexTusExecutor(route, con, data, meta);
        executor.makeAttempts();
        return HttpServletResponse.SC_CREATED;
    }

    /**
     * Convert a metadata object to a map
     * @param meta	The metadata object to convert
     * @return	A map reporting the metadata.
     * @throws MetadataFault	If there are errors converting
     */
    public static Map<String, String> getMetadataAsMap(Metadata meta) throws MetadataFault {
        Map<String, String> map = new TreeMap<>();
        String destId = meta.getDestinationId();
        for (Method m : Metadata.class.getMethods()) {
            JsonProperty jp = m.getAnnotation(JsonProperty.class);
            if (jp != null) {
                String name = jp.value();
                if (name.startsWith("izgw") && !"test".equals(destId)) {
                	// Don't send extension fields to production endpoints.
                	continue;
                }
                try {
                    Object value = m.invoke(meta);
                    if (value != null) {
                    	if (Metadata.TESTFILE.equals(name) && value instanceof Boolean b) {
                    		map.put(name, b ? "yes" : "no");
                    	} else {
                    		map.put(name,  value.toString());
                    	}
                    }
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    throw new MetadataFault(meta, e, String.format("Error retrieving %s", name));
                }
            }
        }
        return map;
    }
    
    private String getToken(IDestination route) throws IOException {
        URL base = new URL(route.getDestUri());
        ExternalTokenStore ts = null;
        ts = ExternalTokenStore.getTokenStore(base, route.getUsername(), route.getPassword());
        ts.setUsingQueryParameters(dexConfig.isUsingQueryParameters());
        ts.setTlsSupport(tlsSupport);
        ts.setDebugging(fiddle);
        String token = ts.getToken();
        log.debug("Obtained Token: {}", token);
        return token;
    }


	@Override
	protected void setExpiration(HttpURLConnection con, Date date) {
		Calendar cal = new GregorianCalendar();
		cal.setTime(date);
		cal.add(Calendar.MINUTE, config.getMaxAgeInMinutes());
		con.setRequestProperty("Upload-Expires", DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.format(date));
	}

	@Override
	protected void checkForSpace(HttpURLConnection con, long fileSize) throws MessageTooLargeFault, IOException {
		long diskFreeSpace = getDiskFreeSpace();
		if ((fileSize + getMaxUploadSize()) <= diskFreeSpace) {
			// There's enough room
			return;
		}
		File[] f = new File(dexConfig.getAppUploadDirectory()).listFiles();
		Arrays.sort(f, DEXStorageSender::fileAgeComparator);
		for (File nextToDelete: f) {
			if (!nextToDelete.isDirectory()) {
				FileUtils.delete(nextToDelete);
			}
			if (fileSize + getMaxUploadSize() < getDiskFreeSpace()) {
				// There's enough room
				return;
			}
		}
		// Still not enough room for this upload and the next, see if there is room for this one
		long freeDiskSpace = getDiskFreeSpace(); 
		if (fileSize < freeDiskSpace) {
			log.warn("Running low on storage: {} free", freeDiskSpace);
		}
		// Not enough room
		log.error("Out of storage for upload: {} free", freeDiskSpace);
		throw new MessageTooLargeFault(MessageTooLargeFault.Direction.REQUEST, freeDiskSpace, fileSize);
	}
	
	protected static int fileAgeComparator(File f1, File f2) {
		return Long.compare(f1.lastModified(), f2.lastModified());
	}


	@Override
	protected String getSubmissionStatus(HttpURLConnection con) throws IOException {
		int result = con.getResponseCode();
		InputStream is;
		if (HttpStatus.OK.value() == result) {
			is = con.getInputStream();
			return IOUtils.toString(is, StandardCharsets.UTF_8);
		} 
		is = con.getErrorStream();
		String error = IOUtils.toString(is, StandardCharsets.UTF_8);
		throw new HttpResponseException(result, error);
	}
}
