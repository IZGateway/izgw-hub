package gov.cdc.izgateway.ads;

import com.fasterxml.jackson.annotation.JsonProperty;

import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.db.service.StatusCheckerService.ADSChecker;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.EventIdMdcConverter;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.logging.event.TransactionData.MessageType;
import gov.cdc.izgateway.logging.event.TransactionData.RequestPayloadType;
import gov.cdc.izgateway.logging.info.DestinationInfo;
import gov.cdc.izgateway.logging.info.SourceInfo;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.security.oauth.ExternalTokenStore;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.soap.fault.*;
import jakarta.activation.DataHandler;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.tuple.Pair;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.xml.ws.http.HTTPException;

// TODO: Add schema documentation 
@Slf4j
@RestController
@CrossOrigin
@RolesAllowed({Roles.OPEN, Roles.ADMIN})
@RequestMapping({"/rest"})
@Lazy(false)
public class ADSController implements ADSChecker {
    private static final List<String> METADATA_FIELDNAMES = getMetadataFieldNames();
    private static final String IZGW_ADS_VERSION = "DEX1.0";
    
    public interface Execute<T> {

        /**
         * Applies this function to the given arguments.
         *
         * @return the function result
         */
        T apply(IDestination r, FileSender s) throws Fault;
    }

    @Configuration
    @Data
    public static class ADSControllerConfiguration {
        private final String mode;
        @Value("${ads.version:V2023-09-01}")
    	private String adsSourceVersion;
    	
        private final IAccessControlService accessControls;
        private final IDestinationService dests;
        private final DEXStorageSender dexFileSender;

        public ADSControllerConfiguration(
        	IAccessControlService accessControls,
	        IDestinationService dests,
	        DEXStorageSender dexFileSender,
	        AppProperties app
        ) {
        	mode = app.getServerMode();
        	this.accessControls = accessControls;
        	this.dests = dests;
        	this.dexFileSender = dexFileSender;
        }
    }
    
    private final ADSControllerConfiguration config;
    @Autowired
    public ADSController(ADSControllerConfiguration config, AccessControlRegistry registry) {
    	this.config = config;
        registry.register(this);

    }

    /**
     * Get the status of the destination.
     * @param xMessageId
     * @param xRequestId
     * @param xCorrelationId
     * @param destinationId
     * @return OK if the service is available, or throws an exception if it is not
     * @throws UnknownDestinationFault  If the destination is not known.
     * @throws MetadataFault    If there is an error in metadata
     * @throws DestinationConnectionFault   If the destination could not be reached
     * @throws HubClientFault   If the destination responded with some sort of error
     * @throws MessageTooLargeFault If the destination has reached its space limits
     */
    @GetMapping("/ads/{destinationId}/status") 
    public String getDestinationStatus(
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @PathVariable String destinationId
    ) throws Fault
    {
        MetadataBuilder m = new MetadataBuilder();
        m.setRouteId(config.getDests(), destinationId);
        m.setMessageId(getMessageId(xMessageId, xCorrelationId, xRequestId));
        m.setProvenance(MetadataBuilder.FACILITY_IZG, RequestContext.getTransactionData());
        m.setFileSize(0);
        
        Metadata meta = m.build();
        meta.setExtEvent("Health Check");

        return logCall(meta, (IDestination r, FileSender f) -> f.getStatus(r));
    }
    
    @DeleteMapping("/ads/{destinationId}/clearTokens") 
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearTokens(
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @PathVariable String destinationId
    ) throws Fault
    {
        MetadataBuilder m = new MetadataBuilder();
        m.setRouteId(config.getDests(), destinationId);
    	ExternalTokenStore.clearTokenStore(m.getDestUrl());
    }
    
	@Override
	public String check(String dest) throws Fault {
		return getDestinationStatus(null, null, null, dest);
	}
	
    @GetMapping("/ads/{destinationId}/status/{tguid}") 
    public Object getSubmissionStatus(
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @PathVariable String destinationId,
        @PathVariable String tguid
    ) throws Fault
    {
        MetadataBuilder m = new MetadataBuilder();
        m.setRouteId(config.getDests(), destinationId);
        m.setMessageId(getMessageId(xMessageId, xCorrelationId, xRequestId));
        m.setProvenance(MetadataBuilder.FACILITY_IZG, RequestContext.getTransactionData());
        m.setFileSize(0);
        
        Metadata meta = m.build();
        meta.setExtEvent("Status Check");
        meta.setPath(tguid);

        return logCall(meta, (IDestination r, FileSender f) -> f.getSubmissionStatus(r, meta));
    }
    
    private void startLogging(Metadata meta) {
    	TransactionData tData = RequestContext.getTransactionData();
        tData.setMessageId(meta == null ? null : meta.getExtObjectKey());
        tData.setMessageType(MessageType.SUBMIT_FILE);
        tData.setRequestPayloadType(RequestPayloadType.fromString(meta == null ? null : meta.getExtEvent()));
    }
    
    private static List<String> getMetadataFieldNames() {
        List<String> props = new ArrayList<>();
        for (Method m: Metadata.class.getMethods()) {
            JsonProperty p = m.getAnnotation(JsonProperty.class);
            if (p != null) {
                props.add(p.value());
            }
        }
        return props;
    }

    @GetMapping(value = "/ads/{destinationId}",
        produces = { 
            "text/plain", 
            "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
            "application/vnd.ms-excel", 
            "application/x-zip-compressed" 
        }
    )
    public ResponseEntity<Resource> getFile( 
        @PathVariable String destinationId,
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @RequestParam String facilityId,
        @RequestParam String reportType,
        @RequestParam String period,
        @RequestParam String filename
    ) throws Fault {
        
        MetadataImpl meta = getMetadata(getMessageId(xMessageId, xCorrelationId, xRequestId), destinationId, facilityId, reportType,
            period, filename, false);
        Pair<InputStream, Map<String, List<String>>> result = getFile(meta);
        HttpHeaders headers = new HttpHeaders();
        Map<String, List<String>> map = result.getRight();
        List<String> values;
        for (String key: METADATA_FIELDNAMES) {
            if ((values = map.get("x-ms-meta-" + key.toLowerCase())) != null) {
                headers.add("x-ms-meta-" + key, values.get(0));
            }
        }
        updateHeaders(headers, HttpHeaders.CONTENT_LENGTH, map);
        updateHeaders(headers, HttpHeaders.CONTENT_TYPE, map);
        updateHeaders(headers, HttpHeaders.LAST_MODIFIED, map);
        updateHeaders(headers, "Content-MD5", map);

        return new ResponseEntity<>(new InputStreamResource(result.getLeft(), meta.getPath()), headers, HttpStatus.OK);
    }
    
    @DeleteMapping(value = "/ads/{destinationId}")
    public ResponseEntity<String> deleteFile( 
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @PathVariable String destinationId,
        @RequestParam String facilityId,
        @RequestParam String reportType,
        @RequestParam String period,
        @RequestParam String filename,
        @RequestParam String reason    // Changes the signature of DELETE command to avoid accidents
    ) throws Fault {
        
        MetadataImpl meta = getMetadata(getMessageId(xMessageId, xCorrelationId, xRequestId), destinationId, facilityId, reportType,
            period, filename, false);

        String result = logCall(meta, (IDestination r, FileSender f) -> f.deleteFile(r, meta));
        
        String when = DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.format(new Date());
        log.info("{} deleted on {} by {}@{}, reason: {}", meta.getPath(), when, meta.getUsername(), meta.getIpAddress(), reason);
        return new ResponseEntity<>(result, null, HttpStatus.ACCEPTED);
    }

    private RestfulFileSender getSender(IDestination route) {
        if (IZGW_ADS_VERSION.equals(route.getDestVersion())) {
            return config.getDexFileSender();
        }
        return null;
    }

    private MetadataImpl getMetadata(String messageId, String destinationId,
        String facilityId, String reportType, String period, String filename, boolean force)
        throws MetadataFault {
        MetadataBuilder m = new MetadataBuilder();
        TransactionData tData = initLogging(destinationId, messageId, m);

        m.setRouteId(config.getDests(), destinationId);
        m.setMessageId(messageId);
        m.setProvenance(facilityId, RequestContext.getTransactionData());
        normalizeReportType(m, reportType);
        
        m.setFileSize(0);
        m.setPeriod(period);
        m.setMetadataValidationEnabled(!force);
        m.setFilename(filename);
        
        if (tData != null) {
        	tData.getDestination().setUrl(m.getDestUrl());
        }
        MetadataImpl meta = m.build();
        
        // Verify destination and event are aligned, don't let people send a routineImmunization to the flu endpoint
        // and vice versa
        checkDestinationAndEvent(meta);

        return meta;
    }

    private void normalizeReportType(MetadataBuilder m, String reportType) {
        Optional<String> normalizedReportType = config.getAccessControls().getEventTypes().stream().filter(e -> e.equalsIgnoreCase(reportType)).findFirst();
        if (normalizedReportType.isPresent()) {
            m.setReportType(normalizedReportType.get());
        } else {
	    	m.getErrors().add(reportType + " is not a valid reportType value. This must be one of " + config.getAccessControls().getEventTypes());
	        m.setReportType(reportType);
        }
    } 

	/**
     * Initialize logging for an inbound request 
     * @param m
     */
    private TransactionData initLogging(String destinationId, String messageId, MetadataBuilder m) {
        TransactionData tData = RequestContext.getTransactionData();
        if (tData != null) {
            tData.setMessageId(messageId);
            
            DestinationInfo destination = tData.getDestination();
            destination.setId(destinationId);
            destination.setUrl(m.getDestUrl());

            SourceInfo source = tData.getSource();
            source.setType("IIS");
            tData.setServiceType("Gateway");
        }
        return tData;
    }
    
    private static void updateHeaders(HttpHeaders headers, String header, Map<String, List<String>> map) {
        List<String> values;
        if (null != (values = map.get(header.toLowerCase()))) {
            headers.add(header, values.get(0));
        }
    }

    private Pair<InputStream, Map<String, List<String>>> getFile(
        MetadataImpl meta 
    ) throws Fault {
    
        TransactionData tData = RequestContext.getTransactionData();
        tData.setMessageType(MessageType.SUBMIT_FILE);
        tData.setRequestPayloadType(RequestPayloadType.fromString(meta.getExtEvent()));
        
        return logCall(meta, (IDestination r, FileSender f) -> f.getFile(r, meta));
    }
  
    /** Set this value to true to read the form from the source file each time.
     * Otherwise it will load the file from the resource once and use that 
     * repeatedly for subsequent calls.
     */
    private static final boolean READ_LIVE = false;

    /** Saved submission form */
    private static String submissionForm = null;
    @GetMapping(value = "/submitFile", produces = MediaType.TEXT_HTML_VALUE) 
    public static String getSubmissionForm() {
    	InputStream is = null;
    	
    	if (READ_LIVE) {
    		try {
				is = new FileInputStream("../src/main/resources/submit.html");
			} catch (FileNotFoundException e) {
				// Fall through, will be thrown as missing submission resource.
			}
    	} else if (submissionForm != null) {
    		return submissionForm;
    	} else {
    		is = ADSController.class.getClassLoader().getResourceAsStream("submit.html");
    	}
    	
    	if (is == null) {
    		throw new ResourceNotFoundException("Missing submission resource");
    	}

    	try {
    		submissionForm = IOUtils.toString(is, StandardCharsets.UTF_8);
    		return submissionForm;
    	} catch (IOException e) {
    		throw new ResourceNotFoundException("Unable to read submission resource", e);
    	}
    }
    
    @PostMapping(value = "/ads/{destinationId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Metadata postADSFile(
        @PathVariable String destinationId,
        @RequestHeader(name="X-Message-ID", required=false) String xMessageId,
        @RequestHeader(name="X-Request-ID", required=false) String xRequestId,
        @RequestHeader(name="X-Correlation-ID", required=false) String xCorrelationId,
        @RequestParam("facilityId") String facilityId,
        @RequestParam("reportType") String reportType,
        @RequestParam("file") MultipartFile file,
        @RequestParam("period") String period,
        @RequestParam(required = false) String filename,
        @RequestParam(defaultValue = "false") boolean force
    ) throws Fault {
        String messageId = null;
        MetadataImpl meta = null;
        long now = System.currentTimeMillis();
    	try {
	        // If no filename value is provided in API call, extract filename from the file component.
	        if (StringUtils.isBlank(filename)) {
	            filename = file.getOriginalFilename();
	            if (filename != null) {
	                if (filename.contains("/")) {
	                    filename = StringUtils.substringAfterLast(filename, '/');
	                }
	                if (filename.contains("\\")) {
	                    filename = StringUtils.substringAfterLast(filename, '\\');
	                }
	            }
	        }
			messageId = getMessageId(xMessageId, xCorrelationId, xRequestId);
	        meta = getMetadata(messageId, destinationId, facilityId, reportType, period, filename, force);
	        meta.setFileSize(file.getSize());
	        log.info(Markers2.append("Source", RequestContext.getSourceInfo()), "New ADS request ({} b) read in {} s",
	        		meta.getFileSize(), (now - RequestContext.getTransactionData().getStartTime()) / 1000);
	        meta.setUploadedDate(new Date(System.currentTimeMillis()));
	        verifyRouting(meta.getDestination());
	        meta.setExtSourceVersion(config.getAdsSourceVersion());
    	} catch (MetadataFault f) {
	    	startLogging(f.getMeta());
	    	throw logException(null, f);
	    }
	        
        DataHandler data = new DataHandler(file, file.getContentType()) {
            @Override
            public InputStream getInputStream() throws IOException {
                return file.getInputStream();
            }
        };

        try {
        	submitFile(meta, data);
        } catch (Fault f) {
        	log.info("Fault occurred", f);
        	throw f;
        }
        return meta;

    }
    
    private void verifyRouting(IDestination iDestination) throws UnknownDestinationFault {
        String version = iDestination.getDestVersion();
        if (IZGW_ADS_VERSION.equals(version)) {
            return;
        }
        throw UnknownDestinationFault.invalidDestination(iDestination.getDestId(), 
        	String.format("This destination is using version %s of the ADS API, it should be using: %s", version, IZGW_ADS_VERSION)
        );
	}

	private void checkDestinationAndEvent(MetadataImpl meta) throws MetadataFault {
        String destinationId = meta.getRouteId();
        String reportType = meta.getExtEvent();
        
        if (!config.getAccessControls().getEventTypes().stream().anyMatch(e -> e.equalsIgnoreCase(reportType))) {
            throw new MetadataFault(meta, String.format("The %s report type is not valid, it must be one of %s", reportType, config.getAccessControls().getEventTypes()));
        }

        if (!config.getAccessControls().isRouteAllowed(destinationId, reportType)) {
            throw new MetadataFault(meta, String.format("The %s report type cannot be sent to %s", reportType, destinationId));
        }
    }

    /**
     * Resolve the value to use for MessageID
     * X-Message-ID, X-Correlation-ID, and X-Request-ID are three different common ways to express
     * an identifier used for tracing requests through distributed systems.  This next block selects
     * the first of these identifiers found in any header parameter in the order listed above.
     * IZ Gateway users are already familiar with MessageId used in SOAP Messages, so it is the preferred
     * parameter.  X-Request-ID and X-Correlation-ID have similar uses, but according to their definitions
     * X-Correlation-ID should be the same for every request used to fulfill a single transaction, whereas
     * X-Request-ID should be different for every request, so X-Correlation-ID is preferred if present when
     * X-Message-ID is not to account for this usage.
     * @param xMessageId    The value in the X-Message-ID header
     * @param xCorrelationId The value in the X-Correlation-ID header
     * @param xRequestId The value in the X-Request-ID header
     * @return the resolved value or a random UUID if no value is set
     */
    
    private String getMessageId(String xMessageId, String xCorrelationId, String xRequestId) {
    	String messageId = StringUtils.firstNonBlank(xMessageId, xCorrelationId, xRequestId);
        return messageId != null ? messageId : UUID.randomUUID().toString();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ADSErrorResponse> handleException(HttpServletRequest req, HttpServletResponse resp, Exception ex) {
        String eventId = MDC.get(EventIdMdcConverter.EVENT_ID_MDC_KEY);
        ADSErrorResponse err = getErrorResponse(ex, eventId);
        log.error(Markers2.append(ex), "{}", ex.getMessage());

        TransactionData tData = RequestContext.getTransactionData();
        if (tData != null) {
            tData.setProcessError(ex);
        	Object response = tData.getResponse();
        	if (response instanceof Map<?,?>) {
        		@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>) response;
        		m.put("error", err);
        	} else {
        		if (response != null) {
        			log.warn(
    					Markers2.append("originalResponse", response),
    					"Error overwrites response object"
    				);
        		}
        		tData.setResponse(singletonMap("error", err));
        	}
        }
        return new ResponseEntity<>(err, updateStatusFromRetryStrategy(ex, err));
    }

    private Map<String, Object> singletonMap(String key, Object value) {
    	Map<String, Object> m = new HashMap<>();
    	m.put(key,  value);
    	return m;
    }
	private ADSErrorResponse getErrorResponse(Exception ex, String eventId) {
		ADSErrorResponse err = null;
        if (ex instanceof Fault f) {
            err = new ADSErrorResponse(f, eventId);
            if (ex instanceof HubClientFault hcf && err.getDetail() == null) {
        		err.setDetail(hcf.getOriginalBody());
        	}
        } else if (ex instanceof IllegalArgumentException) {
            err = new ADSErrorResponse(new UnexpectedExceptionFault("Illegal Argument", null, ex, RetryStrategy.CORRECT_MESSAGE, null), eventId);
            err.setRetryStrategy(RetryStrategy.CORRECT_MESSAGE);
        } else if (ex instanceof ServletRequestBindingException) {
            err = new ADSErrorResponse(new UnexpectedExceptionFault("Error in Request", null, ex, RetryStrategy.CORRECT_MESSAGE, null), eventId);
            err.setRetryStrategy(RetryStrategy.CORRECT_MESSAGE);
        } else if (ex instanceof ResourceNotFoundException) {
        	FaultSupport ms = new MessageSupport(
        		ex.getClass().getSimpleName(), "ResourceNotFound", ex.getMessage(), 
        		null, null, RetryStrategy.CORRECT_MESSAGE
        	); 
        	err = new ADSErrorResponse(ms, eventId);
        } else if (ex instanceof MissingServletRequestPartException msrpex) { 
            err = new ADSErrorResponse(new UnexpectedExceptionFault("Missing Request Parameter", msrpex.getRequestPartName(), ex, RetryStrategy.CORRECT_MESSAGE, null), eventId);
            err.setRetryStrategy(RetryStrategy.CORRECT_MESSAGE);
        } else {
            err = new ADSErrorResponse(new UnexpectedExceptionFault(ex, null), eventId);
        }
		return err;
	}

	
	private HttpStatus updateStatusFromRetryStrategy(Exception ex, ADSErrorResponse err) {
        if (ex instanceof UnknownDestinationFault || ex instanceof ResourceNotFoundException) {
        	// If destination is unknown, return NOT FOUND 
            return HttpStatus.NOT_FOUND;
        } 
        return err.getRetryStrategy().getStatus();
	}

    public Metadata submitFile(
        MetadataImpl meta,
        DataHandler data
    ) throws Fault {
        logCall(meta, (IDestination r, FileSender s) -> s.sendFile(r, data, meta));
        return meta;
    }
    
    /**
     * Perform a call with logging
     * @param meta The Metadata of the request
     * @param call  The call to perform 
     * @throws Fault 
     */
    private <T> T logCall(Metadata meta, Execute<T> call)
        throws Fault {
        startLogging(meta);
        IDestination route = config.getDests().findByDestId(meta.getRouteId());
        
        TransactionData tData = RequestContext.getTransactionData();
        long time = System.currentTimeMillis();
        try {
        	tData.setIisStartTime(time);
        	tData.getSource().setType(SourceInfo.SOURCE_TYPE_ADS);
            return call.apply(route, getSender(route));
        } catch (Exception e) {
        	throw logException(route, e);
        } finally {
            tData.setElapsedTimeIIS(System.currentTimeMillis() - time);
            tData.setResponse(singletonMap("metadata", meta));
        }
    }
    
    private Fault logException(IDestination route, Exception e) {
        TransactionData tData = RequestContext.getTransactionData();
        tData.setResponseReceived(false);
        tData.setResponseHL7Message(null);
        tData.setResponsePayloadSize(0);
        if (e instanceof Fault f) {
            tData.setProcessError(e);
            return f;
        } 
        InputStream error = null;
        int statusCode = 0;
        if (e instanceof ExternalTokenStore.OAuthReportedHttpException oex) {
        	error = IOUtils.toInputStream(oex.getErrorBody(), StandardCharsets.UTF_8);
        	statusCode = oex.getStatusCode();
        } else if (e instanceof HTTPException hex) {
        	statusCode = hex.getStatusCode();
        }
        if (statusCode != 0) {
	        HubClientFault hce = HubClientFault.invalidMessage(e, route, statusCode, error, null); 
	        tData.setProcessError(hce);
	        return hce;
        }
        return new UnexpectedExceptionFault(e, "An unexpected exception occured during upload to " + route.getDestId());
    }
}