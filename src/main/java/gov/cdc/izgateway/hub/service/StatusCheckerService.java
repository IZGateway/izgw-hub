package gov.cdc.izgateway.hub.service;

import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.configuration.StatusCheckerConfiguration;
import gov.cdc.izgateway.dynamodb.model.EndpointStatus;
import gov.cdc.izgateway.logging.LoggingValve;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.logging.event.TransactionData.MessageType;
import gov.cdc.izgateway.logging.info.DestinationInfo;
import gov.cdc.izgateway.logging.info.HostInfo;
import gov.cdc.izgateway.logging.info.SourceInfo;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.security.ClientTlsSupport;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IStatusCheckerService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.FaultSupport;
import gov.cdc.izgateway.soap.message.ConnectivityTestRequest;
import gov.cdc.izgateway.soap.message.ConnectivityTestResponse;
import gov.cdc.izgateway.soap.net.MessageSender;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.utils.SystemUtils;
import gov.cdc.izgateway.utils.X500Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks the connection status of destinations, records the results, and schedules
 * checks that look for a reset of the circuit breaker.
 */
@Slf4j
@Service
public class StatusCheckerService implements IStatusCheckerService {
	@Setter(AccessLevel.PRIVATE)
    private static String statusCheckerEventId = EventId.DEFAULT_TX_ID;
	
	@Setter(AccessLevel.PRIVATE)
    private static String commonName = "unknown";

    private static final int[]  CHECK_INTERVALS = { 15, 60, 120, 180, 240, 300, 600, 900 };

    /**
     * Checks the status of an ADS destination (DEX or Azure).
     */
    public static interface ADSChecker {
		/**
		 * Check the status of an ADS destination.
		 * @param dest	The destination identifier
		 * @return The destination status
		 * @throws Fault	If the status check fails
		 */
		public String check(String dest) throws Fault;
	}

    @Getter
    private final StatusCheckerConfiguration config;
    private final MessageSender messageSender;
    private final IDestinationService destinationService;
	private final ClientTlsSupport clientTlsSupport;
	private final EndpointStatusService endpointStatusService;
	private final ADSChecker adsChecker;
	private final ScheduledExecutorService scheduler;
    
    /**
     * Create the status checker service. Spring supplies the parameters.
     * @param config	The status checker configuration
     * @param messageSender	The sender for connectivity tests
     * @param destinationService	The service that finds destinations
     * @param endpointStatusService	The service that stores endpoint status
     * @param clientTlsSupport	The client TLS support that supplies the client certificate
     * @param adsChecker	The checker for ADS destinations
     * @param app	The application properties that supply the scheduler
     */
    @Autowired
    public StatusCheckerService(
    	StatusCheckerConfiguration config, 
    	MessageSender messageSender, 
    	IDestinationService destinationService, 
    	EndpointStatusService endpointStatusService, 
    	ClientTlsSupport clientTlsSupport,
    	ADSChecker adsChecker,
    	AppProperties app
    ) {
    	this.scheduler = app.getScheduler();
        this.config = config;
        this.messageSender = messageSender;
        this.destinationService = destinationService;
        this.clientTlsSupport = clientTlsSupport;
        this.endpointStatusService =  endpointStatusService;
        this.adsChecker = adsChecker;
        setCommonName(X500Utils.getCommonName(clientTlsSupport.getCertificate()));
        setStatusCheckerEventId(new TransactionData().getEventId());
        messageSender.setStatusChecker(this);
    }
    
    /**
     * Get the names of the hosts that report endpoint status. The list starts with this host.
     * @return The list of host names
     */
    public List<String> getHosts() {
    	List<String> l = new ArrayList<>();
		l.add(SystemUtils.getHostname());
    	for (IEndpointStatus s: endpointStatusService.findAll()) {
    		if (!l.contains(s.getStatusBy())) {
    			l.add(s.getStatusBy());
    		}
    	}
    	return l;
    }
    
	/**
	 * Get the number of failures before the circuit breaker is thrown.
	 * @return The maximum number of failures
	 */
	public int getMaxFailures() {
		return config.getMaxFailuresBeforeCircuitBreaker();
	}
	
	/**
	 * Get the period between status checks.
	 * @return The status check period in minutes
	 */
	public int getStatusCheckPeriodInMinutes() {
		return config.getStatusCheckPeriodInMinutes();
	}
	
	/**
	 * Add a destination to the list of destinations that are exempt from status checks.
	 * @param destId	The destination identifier
	 */
	public void addExemption(String destId) {
		config.getExempt().add(destId);
	}

    /**
     * Check the status of a destination now and save the result.
     * @param dest	The destination to check
     * @return The new status of the destination
     */
    public IEndpointStatus updateDestinationStatus(IDestination dest) {
        EndpointStatus s = new EndpointStatus(dest);
        if (dest.isDex() || dest.isAzure()) {
            doAdsStatusCheck(dest, s);
        } else {
            doHubStatusCheck(s);
        }
        endpointStatusService.save(s);
        return s;
    }

	private void doAdsStatusCheck(IDestination dest, IEndpointStatus s) {
		try {
		    adsChecker.check(dest.getDestId());
		    s.connected();
		} catch (Exception ex) {
		    this.reportFault(s, ex);
		}
	}
	private String doHubStatusCheck(EndpointStatus d) {
        ConnectivityTestRequest ct = new ConnectivityTestRequest();
        String message = String.format("Wishing %s (%s) an Audacious Hello at %tC", d.getDestUri(), d.getDestId(),
                new Date());
        ct.setEchoBack(message);

        String status = null;
        try {
        	IDestination destination = destinationService.findByDestId(d.getDestId());
        	if (destination == null) {
        		return null; // Nobody to send to.
        	}
            ConnectivityTestResponse cr = messageSender.sendConnectivityTest(destination, ct);
            if (cr.getEchoBack() == null) {
                status = "No Echo";
            } else {
            	// We are not verifying that the echoback response matches b/c some IIS fail at this.
                status = d.connected();
            } 
        } catch (Exception e) {
            status = reportFault(d, e);
        }
        d.setStatus(status);
        return status;
    }

    private String reportFault(IEndpointStatus s, Exception e) {
        String status = null;
        if (e instanceof FaultSupport f) {
        	status = f.getSummary();
            s.setStatus(status);
            s.setDetail(f.getDetail());
            s.setDiagnostics(f.getDiagnostics());
            s.setRetryStrategy(f.getRetry().toString());
        } else {
        	status = e.getMessage();
            s.setStatus(status);
            s.setDetail("Unexpected Exception");
            s.setDiagnostics("IZ Gateway threw an unexpected exception.");
            s.setRetryStrategy(RetryStrategy.CONTACT_SUPPORT.toString());
		}

        log.error(Markers2.append(e), "Status check failure on {}", s.getDestId());

        return status;
	}

    /**
     * Check the status of a destination as an internal request, with logging. If the destination
     * is under maintenance, return its current status and do not check it.
     * @param dest	The destination to check
     * @return The status of the destination, or null if dest is null
     */
    public IEndpointStatus checkDestination(IDestination dest) {
        if (dest == null) {
            return null;
        }

        if (dest.isUnderMaintenance()) {
            // Destination is under maintenance, report status, and do nothing further
            return endpointStatusService.getEndpointStatus(dest);
        }

        Map<String, String> map = setupMDC();
        TransactionData tDataOriginal = RequestContext.getTransactionData();
        TransactionData tData = new TransactionData(new Date(), statusCheckerEventId);
        tData.setServiceType("Gateway");
        RequestContext.setTransactionData(tData);
        setDestinationInfoFromDestination(RequestContext.getDestinationInfo(), dest);
        tData.setMessageType(MessageType.CONNECTIVITY_TEST);

        try {

            String requestUri = "/rest/status/" + dest.getDestId();
            MDC.put(LoggingValve.REQUEST_URI, requestUri);

            // Override source type, this is an internal call.
            logInternalSource(tData);

            return updateDestinationStatus(dest);
        } finally {
            tData.logIt();
            RequestContext.setTransactionData(tDataOriginal);
            restoreMDC(map);
        }
    }
    
    /**
     * Copy the URL and identifier of a destination into the destination information for logging.
     * @param info	The destination information to update
     * @param route	The destination to copy from, or null to clear the values
     */
    public static void setDestinationInfoFromDestination(DestinationInfo info, IDestination route) {
		if (route == null) {
			info.setUrl(null);
			info.setId(null);
			return;
		}
		info.setUrl(route.getDestUri());
		info.setId(route.getDestId());
    }
    
    private void logInternalSource(TransactionData tData) {
        SourceInfo source = tData.getSource();

        tData.setCipherSuite("INTERNAL_JAVA_CALL");
        source.setCipherSuite("INTERNAL_JAVA_CALL");
        source.setCertificate(clientTlsSupport.getCertificate());
        source.setFacilityId("IZGW");
        source.setHost(SystemUtils.getHostname());
        source.setId("izgw");
        source.setIpAddress(HostInfo.LOCALHOST_IP4);
        source.setType(SourceInfo.SOURCE_TYPE_INTERNAL);
    }

    private void checkStatus(IDestination dest, int failureCount) {
        try {
        	if (checkDestination(dest).isConnected()) {
        		return;
        	}
        } catch (Exception ex) {
            // Ignore failures.
        }

        // We did not succeed, set checks for the future if there are any to be scheduled.
    	lookForReset(dest, Math.max(failureCount, CHECK_INTERVALS.length - 1));
    }
    
	private void lookForReset(IDestination dest, int count) {
        scheduler.schedule(
            () -> checkStatus(dest, count), StatusCheckerService.CHECK_INTERVALS[count], TimeUnit.SECONDS
        );
	}

	public void lookForReset(IDestination dest) {
		lookForReset(dest, 0);
	}
	
	public boolean isExempt(String destId) {
		return config.getExempt().contains(destId);
	}
	
	
	/**
	 * Update status after successful or failed message send. This keeps status fresh and avoids
	 * unnecessary status checks.
	 * @param status	Current status
	 * @param dest		Destination (needed on failure states to look for a reset of the circuit breaker)
	 * @param reason	The reason for the failure
	 */
	@Override
	public void updateStatus(IEndpointStatus status, IDestination dest, Fault reason) {
		boolean wasCircuitBreakerThrown = status.isCircuitBreakerThrown();
		if (reason == null) {
			status.connected();
			if (wasCircuitBreakerThrown) {
				logCircuitBreakerReset(status);
			}
		} else {
			status = new EndpointStatus(dest).fromFault(reason);
			if (!wasCircuitBreakerThrown && reason.shouldBreakCircuit()) {
				status.setStatus(IEndpointStatus.CIRCUIT_BREAKER_THROWN);
				logCircuitBreakerThrown(status, reason);
				lookForReset(dest);
			}
		}
		endpointStatusService.save(status);
	}
	

	@Override
    public void logCircuitBreakerReset(IEndpointStatus status) {
        // Ensure that there is an event id if not set.
        Map<String, String> mdcValues = setupMDC();
        // This was previously a broken connection.  We've reset the circuit breaker,
        // log that event.
        log.info(Markers2.append("status", status),
                "Circuit Breaker Reset for {} ({}) in {}",
                status.getDestId(), status.getDestUri(), SystemUtils.getDestTypeAsString());
        restoreMDC(mdcValues);
    }

	@Override
    public void logCircuitBreakerThrown(IEndpointStatus status, Throwable why) {
        Map<String, String> mdcValues = setupMDC();
        log.info(Markers2.append(why, "status", status),
                "Circuit Breaker Thrown for {} ({}) in {}: {}", status.getDestId(),
                status.getDestUri(), SystemUtils.getDestTypeAsString(),
                status.getStatus());
        restoreMDC(mdcValues);
    }
    
    /**
     * Set the MDC logging values for an internal request, if they are not already set.
     * @return The MDC values before the change, for use with {@link #restoreMDC(Map)}
     */
    public static Map<String, String> setupMDC() {
        // Set various MDC values that are expected to appear in logs to reasonable values that would tell folks about
        // where this request came from.
        Map<String, String> m = new HashMap<>();
        LoggingValve.MDC_EVENTS.forEach(s -> m.put(s,  MDC.get(s)));
        // Don't mess with MDC if values are already present.
        if (MDC.get(LoggingValve.EVENT_ID) != null && MDC.get(LoggingValve.REQUEST_URI) != null) {
        	return m;
        }
        MDC.put(LoggingValve.EVENT_ID, statusCheckerEventId);
        MDC.put(LoggingValve.REQUEST_URI, Thread.currentThread().getName());
        MDC.put(LoggingValve.METHOD, "INTERNAL");
        MDC.put(LoggingValve.IP_ADDRESS, HostInfo.LOCALHOST_IP4);
        MDC.put(LoggingValve.COMMON_NAME, commonName);

        return m;
    }

    /**
     * Restore the MDC logging values that {@link #setupMDC()} returned.
     * @param m	The MDC values to restore
     */
    public static void restoreMDC(Map<String, String> m) {
        for (Map.Entry<String, String> e: m.entrySet()) {
            if (e.getValue() == null) {
                MDC.remove(e.getKey());
            } else {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }

	/**
	 * Get the scheduler that runs the status checks.
	 * @return The scheduler
	 */
	public ScheduledExecutorService getScheduler() {
		return scheduler;
	}
}
