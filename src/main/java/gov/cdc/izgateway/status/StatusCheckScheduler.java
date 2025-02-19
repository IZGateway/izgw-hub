package gov.cdc.izgateway.status;

import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.StatusCheckerService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.fault.FaultSupport;
import gov.cdc.izgateway.soap.fault.MessageSupport;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * This class is used to check the status of existing connections.
 * It wakes up every 5 minutes and checks anything that it hasn't checked
 * whose age is older than frequencyInMinutes, and everything that hasn't been
 * checked in 5 minutes + frequencyInMinutes.
 */
@Slf4j
@Component
@Lazy(false)
public class StatusCheckScheduler {
    /** How often should an instance resync with other instances on the schedule */
    private static final Duration RESYNC_DURATION = Duration.ofMinutes(5); // Every 5 minutes
    private static final int RESYNCS_PER_CYCLE = 3;
    /** How long should it take to check everything, must be a multiple of RESYNC_DURATION */
    private static final Duration CYCLE_DURATION = Duration.ofMinutes(RESYNC_DURATION.toMinutes() * RESYNCS_PER_CYCLE); // Every 15 minutes
    /** How long should it take for all nodes to start up */
    private static final Duration STARTUP_ALLOWANCE = Duration.ofMinutes(3); // Three minutes
    private static final FastDateFormat TIMESTAMP_FORMATTER = FastDateFormat.getInstance(Constants.TIMESTAMP_FORMAT);
    private static final boolean DISABLED = false;  // Set to true to disable for debugging purposes.
    private final StatusCheckerService statusCheckerService;
    private final EndpointStatusService endpointStatusService;
    private final DestinationService dests;
	private final ScheduledExecutorService scheduler;

    @Autowired
    public StatusCheckScheduler(StatusCheckerService statusCheckerService, EndpointStatusService endpointStatusService, DestinationService dests) {
        this.statusCheckerService = statusCheckerService;
        this.endpointStatusService = endpointStatusService;
        this.dests = dests;
        this.scheduler = statusCheckerService.getScheduler();
    }

    /**
     *	Bridges the diagnostic lookup in the repository.
     * @param faultName The name of the fault
     * @param faultCode The code of the fault
     * @return The diagnostic string for the fault
     */
    public String getDiagnostics(String faultName, String faultCode) {
        FaultSupport s = MessageSupport.getTemplate(faultCode, faultName);
        return s == null ? null : s.getDiagnostics();
    }

    public void start() {
        log.info("Starting Status Checker");
        // Exempt testing endpoints from status checks, a) don't waste time, these will fail, and don't circuit break them
        // because then we'll need to reset the breaker, and the only way to do that is to get a good connection, which can
        // never happen with these.
        dests.getAllDestinations().stream().filter(this::isTestingEndpoint).forEach(d -> statusCheckerService.addExemption(d.getDestId()));
        // Initialize status from Elastic.  If this fails (e.g., b/c of a configuration error), ensure IZGW won't start
        // properly.
        if (!endpointStatusService.refresh()) {
            log.error("Could not initialize Status Service");
            throw new ServiceConfigurationError("Could not initialize Status Service");
        }
    	statusCheckerService.checkDestination(dests.findByDestId("dev"));

        scheduler.scheduleAtFixedRate(this::setSchedule, getNextSyncTime(), RESYNC_DURATION.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Status Checker started");
    }

    /**
     * The StatusChecker is going to run at least every 5 minutes, and all operating nodes will sync up
     * every five minutes on the schedule.
     *
     * @return	The next time to sync up, which is every 5 minutes starting at the hour, but not less than two minutes into the future
     */
    private long getNextSyncTime() {
        long resyncTime = RESYNC_DURATION.toMillis();
        // next = next five minute interval that is no less than 3 minutes into the future, but no more than 8 minutes.
        long nextSyncTime = resyncTime - (System.currentTimeMillis() % resyncTime);
        if (nextSyncTime < STARTUP_ALLOWANCE.toMillis()) {
            nextSyncTime += RESYNC_DURATION.toMillis();
        }
        return nextSyncTime;
    }

    private void setSchedule() {
        Map<String, String> map = StatusCheckerService.setupMDC();
        try {
            // First thing to do is refresh status and destinations.
            log.debug("Updating schedule");
            endpointStatusService.refresh();
            dests.refresh();

            List<IDestination> dList = Arrays.asList(
                    dests.getAllDestinations().stream()
                            .filter(this::isInteresting).toArray(len -> new IDestination[len])
            );
            IEndpointStatus[] sList = endpointStatusService.findAll().toArray(new IEndpointStatus[0]);
            List<String> hosts = statusCheckerService.getHosts();

            // OK, at two minutes into the future, all nodes have generally agreed on:
            // which destinations need to be checked.
            // what the current status of those destinations are.

            int myInstanceOffset = 0;
            for (IEndpointStatus s: sList) {
                if (!hosts.contains(s.getStatusBy())) {
                    if (s.getStatusBy().equalsIgnoreCase(SystemUtils.getHostname())) {
                        myInstanceOffset = hosts.size();
                    }
                    hosts.add(s.getStatusBy());
                }
            }
            // Hosts will take a position in sorted order by name.
            hosts.sort(String::compareTo);
            dList.sort((ths,that) -> ths.getDestId().compareTo(that.getDestId()));

            buildMySchedule(dList, hosts, myInstanceOffset);
        } finally {
        	StatusCheckerService.restoreMDC(map);
        }
    }

    /**
     * Report on destinations that a status check will serve some purpose for.
     * - Nodes that aren't under maintenace,
     * - operated by THIS code
     * - or specifically exempt b/c they don't support that API call yet
     * @param d	The destination to check
     * @return
     */
    private boolean isInteresting(IDestination d) {
        boolean isUnderMaintenance = d.isUnderMaintenance();
        boolean isWorthChecking = isWorthChecking(d);
        boolean isExempt = isExempt(d.getDestId());
        return !isUnderMaintenance && isWorthChecking && !isExempt;
    }

    /**
     * Build the schedule of status checks for this instance.
     * Assumptions:  All instances are up and running, and have agreed upon the dList and hosts available.
     * @param dList
     * @param hosts
     * @param myInstanceOffset
     */
    private void buildMySchedule(List<IDestination> dList, List<String> hosts, int myInstanceOffset) {
        log.debug("Building status check schedule for {}", SystemUtils.getHostname());
        long now = System.currentTimeMillis();
        int numberOfDestsToCheckPerSync = (dList.size() + RESYNCS_PER_CYCLE - 1) / RESYNCS_PER_CYCLE;
        long periodBetweenChecks = RESYNC_DURATION.toMillis() / numberOfDestsToCheckPerSync;
        /** whatPartOfListToCheck will be an integer between 0 and RESYNCS_PER_CYCLE - 1 */
        int whatPartOfListToCheck = (int) ((now % CYCLE_DURATION.toMillis()) / RESYNC_DURATION.toMillis());
        int startingDestOffset = numberOfDestsToCheckPerSync * whatPartOfListToCheck;

        for (int i = 0; i < numberOfDestsToCheckPerSync; i += hosts.size()) {
            int index = i + myInstanceOffset + startingDestOffset;
            if (index >= dList.size()) {
                break;
            }
            scheduler.schedule(
                    () -> checkDestination(dList.get(index).getDestId()),
                    (index+1) * periodBetweenChecks, TimeUnit.MILLISECONDS
            );

            log.debug(
                    "Status Check on {} scheduled for {} by {}",
                    dList.get(index).getDestId(),
                    TIMESTAMP_FORMATTER.format(now + (index+1) * periodBetweenChecks),
                    SystemUtils.getHostname()
            );
        }
    }

    private boolean isExempt(String destId) {
        return statusCheckerService.getConfig().getExempt().contains(destId);
    }

    private IEndpointStatus checkDestination(String destId) {
        long minRecentCheck = System.currentTimeMillis() - RESYNC_DURATION.toMillis();
        // Get the most current configuration.
        IDestination dest = dests.findByDestId(destId);
        // And the most recent status.
        IEndpointStatus status = endpointStatusService.findById(destId);

        if (DISABLED) {
        	return status;
        }
        // If there is a status, and it is connected, and it is recent
        if (status != null && status.isConnected() && status.getStatusAt().getTime() > minRecentCheck) {
            // Just return the current status, we don't need a check for endpoints that are in frequent use and working.
            return status;
        }

        // But, if we don't know the status, or the last status was not recent, or it wasn't connected, check it.
        status = statusCheckerService.checkDestination(dest);

        return status;
    }

    private static final String[] NOT_WORTH_CHECKING_PREFIXES = {
        "/", // Local URI
        "https://localhost", // Also a Local URI
        "https://192.0", // A test URI
        "https://iis.invalid" // A test URI
    };

    private boolean isTestingEndpoint(IDestination d) {
        return statusCheckerService.getConfig().getTestingEndpoints().contains(d.getDestId());
    }

    private boolean isWorthChecking(IDestination d) {
        if (d == null) {
            return false;
        }
        // We need not check endpoints which are local to this server.
        String uri = d.getDestUri();
        for (String prefix: NOT_WORTH_CHECKING_PREFIXES) {
            if (StringUtils.startsWith(uri, prefix)) {
                return false;
            }
        }
        return true;
    }
}
