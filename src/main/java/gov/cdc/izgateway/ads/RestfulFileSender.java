package gov.cdc.izgateway.ads;

import com.fasterxml.jackson.annotation.JsonProperty;

import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.logging.info.HostInfo;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.ClientTlsSupport;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.StatusCheckerService;
import gov.cdc.izgateway.soap.fault.*;
import gov.cdc.izgateway.utils.*;
import jakarta.activation.DataHandler;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpResponseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.*;
import org.apache.http.message.BasicHeader;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsFatalAlertReceived;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.xml.ws.http.HTTPException;

/**
 * This class implements the FileSender interface to Azure and the Azurite Azure emulator.
 */
@Slf4j
@Component
public abstract class RestfulFileSender implements FileSender {
    private static final String FILENAME_INVALID = "Filename invalid";
    private static final File STORAGE = new File(".");
	protected static final int CHUNK_SIZE = 2 << 20; // 2 MB
	/** Set to true to see output in debug console */
    protected boolean fiddle = false;
    protected final ClientTlsSupport tlsSupport;
    
    // Fixed values in metadata
    @Configuration 
    public static class SenderConfig {
        @Value("${ads.maxAge:120}")
        private int maxAgeInMinutes;
        
        /** Maximum expected size of a file upload (15Gb) */
        @Value("${ads.max-message-size-GB:15}")
        private int maxUploadSizeInGB;
        
        private final IDestinationService destinationService;
        
        @Autowired
        public SenderConfig(IDestinationService destinationService) {
        	this.destinationService = destinationService;
        }

        public IDestinationService getDestinationService() {
        	return destinationService;
        }
        
        public int getMaxAgeInMinutes() {
        	return maxAgeInMinutes;
        }
        
        public int getMaxUploadSizeInGB() {
        	return maxUploadSizeInGB;
        }
    }
    
    private static final List<String> LOCALHOST = Arrays.asList(HostInfo.LOCALHOST_IP4, HostInfo.LOCALHOST_IP6, HostInfo.LOCALHOST);
	protected final SenderConfig config;
    
    protected RestfulFileSender(SenderConfig config, ClientTlsSupport tlsSupport) {
    	this.config = config;
    	this.tlsSupport = tlsSupport;
    }
    /**
     *  Connect the socket to the phiz_trust_ws_client trust store for endpoint certificate validation.
     *  and set up of other parameters (e.g., encryption, TLS version, et cetera).
     * @param string 
     *  
     * @param route  The URL to Connect
     * @param object 
     * @param meta 
     * @return  The connection
     * @throws IOException  If any errors occur setting up the connection
     * @throws MetadataFault If there are issues in the metadata configuration.
     * @throws DestinationConnectionFault
     */
    protected abstract HttpURLConnection getConnection(String string, IDestination route, Metadata meta, DataHandler object) throws IOException, MetadataFault, DestinationConnectionFault, URISyntaxException;

    /**
     * Copy data stored in DataHandler to the URLConnection.
     * @return 
     * @throws MetadataFault 
     * @throws ProtocolException 
     * @throws DestinationConnectionFault
     */
    protected abstract int writeData(HttpURLConnection con, IDestination route, DataHandler data, Metadata meta) throws IOException, MetadataFault, DestinationConnectionFault, io.tus.java.client.ProtocolException;

    
    @Override
    public HttpURLConnection sendFile(IDestination route, DataHandler data, Metadata meta) throws MessageTooLargeFault, HubClientFault, DestinationConnectionFault, MetadataFault, SecurityFault {
        
        // Create the HTTP URL to send
        long elapsedTimeIIS = 0;
        InputStream error = null;
        try {
            meta.setUploadedDate(new Date());
            HttpURLConnection con = getConnection("POST", route, meta, data);
            checkForSpace(con, route, meta.getFileSize());

            elapsedTimeIIS = -System.currentTimeMillis();
            
            TransactionData tData = RequestContext.getTransactionData();
            tData.setMessageId(meta.getExtObjectKey());
            StatusCheckerService.setDestinationInfoFromDestination(tData.getDestination(), route);

            if (isTestEndpoint(route)) {
            	setExpiration(con, meta.getUploadedDate());
            }
            
            con.connect();
            tData.getDestination().setFromConnection(con);

            int responseCode = writeData(con, route, data, meta);
            
            RequestContext.getTransactionData().setElapsedTimeIIS(elapsedTimeIIS);
            if (responseCode != HttpStatus.CREATED.value() && responseCode > 0) {
                error = con.getErrorStream();
                // Don't report the SAS Token in logs, that is Sensitive information.
               throw new HTTPException(responseCode);
            }
            else if (responseCode != HttpStatus.CREATED.value() && responseCode  <= 0) {
                throw new HTTPException(responseCode);
            }
            return con;
        } catch (URISyntaxException e) {
           throw HubClientFault.invalidMessage(e, route, 0, null, null);
        } catch (io.tus.java.client.ProtocolException e) {
        	throw HubClientFault.invalidMessage(e, route, 0, 
        		IOUtils.toInputStream(e.getMessage(), StandardCharsets.UTF_8), null);
        } catch (MalformedURLException e) {
            throw new MetadataFault(meta, e, FILENAME_INVALID);
        } catch (IOException e) {
            if (elapsedTimeIIS < 0) {
                elapsedTimeIIS += System.currentTimeMillis();
            }
            checkException(route, elapsedTimeIIS, ObjectUtils.defaultIfNull(ExceptionUtils.getRootCause(e),e), error);
            return null;
        }  finally {
            elapsedTimeIIS += System.currentTimeMillis();
            RequestContext.getTransactionData().setElapsedTimeIIS(elapsedTimeIIS);
        }
    }

    protected abstract void setExpiration(HttpURLConnection con, Date date);

    /**
     * Check for available space in storage.
     * @param con	The connection
     * @param route	The route
     * @param fileSize	The amount of storage needed
     * @throws MessageTooLargeFault	If enough storage cannot be reserved.
     */
    private void checkForSpace(HttpURLConnection con, IDestination route, long fileSize) throws MessageTooLargeFault, IOException {
    	long maxUploadSize = config.getMaxUploadSizeInGB();
    	maxUploadSize <<= 30l; // Shorthand to Multiply by 1 gigabyte.
        if (fileSize > maxUploadSize) {
            throw new MessageTooLargeFault(MessageTooLargeFault.Direction.REQUEST, maxUploadSize, fileSize);
        }
        if (isTestEndpoint(route)) {
        	checkForSpace(con, fileSize);
        }
    }

    /**
     * Checks for, and possibly makes space for the upload.
     * @param fileSize The amount of space needed.
     * @throws MessageTooLargeFault 
     */
    protected abstract void checkForSpace(HttpURLConnection con, long fileSize) throws MessageTooLargeFault, IOException;
	/**
     * Check to see if the endpoint is for testing (runs on localhost)
     * @param route  The url being tested
     * @return  True if a test endpoint, false otherwise
     */
    private boolean isTestEndpoint(IDestination route) {
        return LOCALHOST.contains(route.getDestinationUri());
    }

    @Override
    public String getSubmissionStatus(IDestination route, Metadata meta) throws DestinationConnectionFault, MetadataFault, HubClientFault {
        HttpURLConnection con;
		try {
			con = getConnection("STATUS", route, meta, null);
			if (con == null) {
				throw new UnsupportedOperationException();
			}
	        return getSubmissionStatus(con);
		} catch (HttpResponseException ex) {
			throw HubClientFault.httpError(route, ex.getStatusCode(), ex.getMessage());
		} catch (URISyntaxException | IOException e) {
			throw HubClientFault.invalidMessage(e, route, 0, null, null);
        }
    }

	protected abstract String getSubmissionStatus(HttpURLConnection con) throws IOException;

	@Override
    public final Pair<InputStream, Map<String, List<String>>> getFile(IDestination route, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault {
        // Create the HTTP URL to send
        long elapsedTimeIIS = 0;
        InputStream error = null;
        try {
            HttpURLConnection con = getConnection("GET", route, meta, null);
            elapsedTimeIIS = -System.currentTimeMillis();
            
            TransactionData tData = RequestContext.getTransactionData();
            tData.setMessageId(meta.getExtObjectKey());
            StatusCheckerService.setDestinationInfoFromDestination(tData.getDestination(), route);
            
            con.connect();
            tData.getDestination().setFromConnection(con);

            int responseCode = con.getResponseCode();
            elapsedTimeIIS += System.currentTimeMillis();
            
            if (responseCode != HttpStatus.OK.value()) {
                try (InputStream is = con.getErrorStream();) {
                    if (is != null) {
                        error = is;
                    }
                }
                throw new HTTPException(responseCode);
            }
            
            return Pair.of(con.getInputStream(), con.getHeaderFields());
        } catch (MalformedURLException e) {
            throw new MetadataFault(meta, e, FILENAME_INVALID);
        } catch (IOException | URISyntaxException e) {
            if (elapsedTimeIIS < 0) {
                elapsedTimeIIS += System.currentTimeMillis();
            }
            checkException(route, elapsedTimeIIS, ObjectUtils.defaultIfNull(ExceptionUtils.getRootCause(e), e), error);
            return null;
        }
    }
    

    @Override
    public final String deleteFile(IDestination route, Metadata meta) throws MetadataFault, HubClientFault, DestinationConnectionFault, SecurityFault {
        // Create the HTTP URL to send
        long elapsedTimeIIS = 0;
        InputStream error = null;
        try {
            HttpURLConnection con = getConnection("DELETE", route, meta, null);
            
            elapsedTimeIIS = -System.currentTimeMillis();
            
            TransactionData tData = RequestContext.getTransactionData();
            tData.setMessageId(meta.getExtObjectKey());

            StatusCheckerService.setDestinationInfoFromDestination(tData.getDestination(), route);

            con.connect();
            tData.getDestination().setFromConnection(con);

            int responseCode = con.getResponseCode();
            elapsedTimeIIS += System.currentTimeMillis();
            
            if (responseCode != HttpStatus.ACCEPTED.value()) {
                try (InputStream is = con.getErrorStream();) {
                    if (is != null) {
                        error = is;
                    }
                }
                throw new HTTPException(responseCode);
            }
            return "ACCEPTED";
        } catch (MalformedURLException e) {
            throw new MetadataFault(meta, e, FILENAME_INVALID);
        } catch (IOException | URISyntaxException | HTTPException e) {
            if (elapsedTimeIIS < 0) {
                elapsedTimeIIS += System.currentTimeMillis();
            }
            checkException(route, elapsedTimeIIS, ObjectUtils.defaultIfNull(ExceptionUtils.getRootCause(e),e), error);
            return "FAIL"; // Never gets here.  ValidationHelper.checkException always throws.
        }
    }
    
    /**
     * extract the metadata values as Headers for an HTTP PUT operation
     * @param meta  The metadata object to extract headers from.
     * @return  The HTTP Headers to use with Blob storage
     * @throws IOException 
     */
    public List<Header> getHeaders(Metadata meta, DataHandler data) throws IOException {
        List<Header> headers = new ArrayList<>();
        
        // Compute integrity check data first, because it is needed to
        // set the file size before the header is generated.
        IntegrityCheck ic = null;
        if (data != null) {
            ic = IntegrityCheck.getIntegrityCheck(data);
            headers.add(new BasicHeader("Content-MD5", ic.toString()));
            headers.add(new BasicHeader("Content-Length", Long.toString(ic.getLength())));
            meta.setFileSize(ic.getLength());
        }
        headers.add(new BasicHeader("x-ms-blob-type", "BlockBlob"));
        addHeadersFromMetadata(meta, headers);
        // Set the Content-Type from the filename.
        switch (StringUtils.substringAfterLast(meta.getFilename(), ".").toLowerCase()) {
        case "txt":
            
            headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "text/plain"));
            break;
        case "xlsx":
            headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            break;
        case "xls":
            headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/vnd.ms-excel"));
            break;
        case "zip":
            headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/x-zip-compressed"));
            break;
        default:
            if (ic != null && ic.hasMimeType()) {
                headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, ic.getMimeType()));
            } else {
                headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/x-zip-compressed"));
            }
            break;
        }
        headers.add(new BasicHeader("x-ms-client-request-id", meta.getExtObjectKey()));
        return headers;
    }

    private void addHeadersFromMetadata(Metadata meta, List<Header> headers) {
    	boolean isProd = !"test".equals(meta.getDestinationId());
    	
        for (Method m: Metadata.class.getMethods()) {
            JsonProperty ann = m.getAnnotation(JsonProperty.class);
            if (ann != null) {
                String name = ann.value();
                String value = null;
                if (name.startsWith("izgw") && isProd) {
                	// Don't send IZGW Extension fields to production environments
                	continue;
                }
                try {
                    Object o = m.invoke(meta);
                    if (o != null && m.getReturnType() == Date.class) {
                   		value = Metadata.RFC2616_DATE_FORMAT.format(m.invoke(meta));
                    } else if (o != null) {
                        value = o.toString();
                    }
                } catch (Exception e) {
                    log.error(Markers2.append(e), "Error serializing Metadata: {}", e.getMessage(), e);
                }
                headers.add(new BasicHeader("x-ms-meta-" + name, value));
            }
        }
    }

    @Override
    public final String getStatus(IDestination route) throws HubClientFault, MetadataFault, DestinationConnectionFault, SecurityFault {
        HttpURLConnection con = null;
        long elapsedTimeIIS = 0;
        try {

            elapsedTimeIIS = -System.currentTimeMillis();
            con = getConnection("PING", route, null, null);
            StatusCheckerService.setDestinationInfoFromDestination(RequestContext.getDestinationInfo(), route);
            
            if (fiddle && con instanceof HttpsURLConnection conx) {
            	conx.setSSLSocketFactory(new CapturingSSLSocketFactory(conx.getSSLSocketFactory()));
            }
            con.connect();
            elapsedTimeIIS += System.currentTimeMillis();

            int responseCode = con.getResponseCode();
            if (responseCode > 0) {
                RequestContext.getDestinationInfo().setFromConnection(con);
            }
            @SuppressWarnings("unused")
			String result = null;
            InputStream is = null;
            if (responseCode != HttpStatus.OK.value() && 
            	responseCode != HttpStatus.NO_CONTENT.value()) {
            	throw new HTTPException(responseCode);
            }
            @SuppressWarnings("unused")
			Map<String, List<String>> headers = con.getHeaderFields();
        	is = (responseCode >= 200 && responseCode < 300)  ? con.getInputStream() : con.getErrorStream();
        	result = is != null ? IOUtils.toString(is, StandardCharsets.UTF_8) : null;  // NOSONAR: result is for debugging purposes
            return route.getDestUri();
        } catch (IOException | HTTPException | URISyntaxException e) {
            if (con == null) {
                //check if the Connect Exception is ActiveReject or Timeout
                checkException(route, elapsedTimeIIS, ObjectUtils.defaultIfNull(ExceptionUtils.getRootCause(e), e), null);
                throw HubClientFault.invalidMessage(e, route, 0, null, null);
            }
            
            InputStream is = con.getErrorStream();
            throw HubClientFault.invalidMessage(e, route, 0, is, null);
        }
    }
    
    /**
     *  Connect the socket to the phiz_trust_ws_client trust store for endpoint certificate validation.
     *  and set up of other parameters (e.g., encryption, TLS version, et cetera).
     *  
     * @param base  The URL to Connect
     * @return  The connection
     * @throws IOException  If any errors occur setting up the connection
     */
    protected HttpURLConnection getConnection(URL base) throws IOException {
    	return tlsSupport.getSNIEnabledConnection(base);
    }
    
    public static URL getUrl(Metadata meta, IDestination r)
        throws MetadataFault {
        try {
            URI base = new URI(r.getDestinationUri());
            base = base.resolve(ADSUtils.createUrl(base, meta));
            return new URL(base.getScheme(), base.getHost(), base.getPort(), base.getPath() + "?" + r.getPassword());
        } catch (MalformedURLException | URISyntaxException e) {
            throw new MetadataFault(meta, e, e.getMessage());
        }
    }

    protected long getDiskFreeSpace() {
    	return STORAGE.getFreeSpace();
    }
    
    protected long getMaxUploadSize() {
    	return config.getMaxUploadSizeInGB() * 1073741824l;
    }
    
	public SSLSocketFactory getSslSocketFactory() {
		return tlsSupport.getSSLContext().getSocketFactory();
	}
	/**
	 * Reusable portion of ValidateResponse refactored out for
	 * reuse by ADS Service.
	 * @throws SecurityFault 
	 */
	public static void checkException(IDestination routing, long elapsedTimeIIS, Throwable rootCause, InputStream error) throws HubClientFault, DestinationConnectionFault, SecurityFault {
	    StackTraceElement[] stackTrace = rootCause.getStackTrace();
	
	    if (rootCause instanceof DestinationConnectionFault dcf) {
	    	// Handle circuit breaker case.
	    	throw dcf;
	    }
	    
	    if (rootCause instanceof SecurityFault sf) {
	    	throw sf;
	    }
	    
	    if (rootCause instanceof UnknownHostException uhe) {
	        rootCause.setStackTrace(Arrays.copyOfRange(stackTrace, 0, 3));
	        throw DestinationConnectionFault.unknownHost(routing, uhe);
	    }
	
	    if (rootCause instanceof SocketTimeoutException ste) {
	        rootCause.setStackTrace(Arrays.copyOfRange(stackTrace, 0, 3));
	        throw DestinationConnectionFault.timeoutError(routing, ste, elapsedTimeIIS);
	    }
	
	    if (rootCause instanceof ConnectException ce) {
	        rootCause = ExceptionUtils.getRootCause(rootCause);
	        rootCause.setStackTrace(Arrays.copyOfRange(stackTrace, 0, 3));
	        throw DestinationConnectionFault.connectError(routing, ce, elapsedTimeIIS);
	    }
	    
	    if (rootCause instanceof TlsFatalAlertReceived tlsErr) {
	        rootCause.setStackTrace(Arrays.copyOfRange(stackTrace, 0, 3));
	    	throw DestinationConnectionFault.tlsErrorAtDestination(routing, tlsErr, elapsedTimeIIS);
	    } 
	    
	    if (rootCause instanceof TlsFatalAlert tlsErr) {
	        rootCause.setStackTrace(Arrays.copyOfRange(stackTrace, 0, 3));
	    	throw DestinationConnectionFault.tlsErrorAtIZGW(routing, tlsErr, elapsedTimeIIS);
	    }
	    
	    int statusCode = 0;
	    if (rootCause instanceof HTTPException httpEx) {
	    	statusCode = httpEx.getStatusCode();
	    }
	    if (!(rootCause instanceof FaultSupport) &&
	    	!(rootCause instanceof HTTPException) &&
	    	!(rootCause instanceof CertificateException)) {
	        // This is an unexpected exception in the response.
	        log.error(Markers2.append(rootCause), "Unexpected Exception: {}", rootCause.getMessage(), rootCause);
	    }
	    throw HubClientFault.invalidMessage(rootCause, routing, statusCode, error, null);
	}
}
