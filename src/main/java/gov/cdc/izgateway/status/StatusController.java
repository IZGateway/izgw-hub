package gov.cdc.izgateway.status;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.db.RefreshQueueService;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.dynamodb.model.EndpointStatus;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.StatusCheckerService;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.utils.SystemUtils;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.utils.ExecUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.security.RolesAllowed;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@CrossOrigin
@RolesAllowed({Roles.USERS, Roles.ADMIN})
@RequestMapping({"/rest"})
@Lazy(false)
public class StatusController {
    private final EndpointStatusService  endpointStatusService;
	private final StatusCheckerService checkerService;
	private final DestinationService destinationService;
	private final RefreshQueueService refreshQueueService;
    @Autowired
    public StatusController(EndpointStatusService endpointStatusService, StatusCheckerService checkerService, AccessControlRegistry registry, DestinationService destinationService, RefreshQueueService refreshQueueService) {
        this.endpointStatusService = endpointStatusService;
        this.checkerService = checkerService;
        this.destinationService = destinationService;
        this.refreshQueueService = refreshQueueService;
        registry.register(this);
    }

    @Operation(summary = "Get the status history",
            description = "Returns the status history of the destinations")
    @ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation= EndpointStatus.Map.class)
            )
    )
    @GetMapping("/statushistory")
    public Map<String, List<IEndpointStatus>> searchStatusHistory(
    		@RequestParam(name = "include", required = false) String include,
    		@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "count", defaultValue = "1") int count) {
        if (count <= 0) {
            count = 1;
        } else if (count > 4) {
            count = 4;
        }
        String[] includeArray = include != null ? include.split("[\\s,]+") : 
        	destinationService.getAllDestinations().stream().map(t -> t.getDestinationId()).toArray(String[]::new);
        List<String> included = Arrays.asList(includeArray);
        included.sort(String::compareTo);
        List<? extends IEndpointStatus> found = endpointStatusService.find(count, includeArray);

        Map<String, List<IEndpointStatus>> t = collectHistory(status, included, found);
        addMisssingHistory(includeArray, t);
        return t;
    }

	private Map<String, List<IEndpointStatus>> collectHistory(String status, List<String> included,
			List<? extends IEndpointStatus> found) {
		Map<String, List<IEndpointStatus>> t = new TreeMap<>();
        for (IEndpointStatus f : found) {
            if (included.isEmpty() || included.contains(f.getDestId())) {
                // To check for history values of a certain status, specify ?status=<DesiredStatus> in the URL
                // To check for history values of not having a certain status, specifiy ?status=!<Desired Status> in the URL
            	// This enables quick checks for those endpoints with a status of other than connected.
                if (!StringUtils.isEmpty(status)) {
                	boolean test = !status.startsWith("!");
                	if (Strings.CI.equals(f.getStatus(), status) != test) {
                		continue;
                	}
                }
                
                List<IEndpointStatus> l = t.computeIfAbsent(f.getDestId(), k -> new ArrayList<>());
                l.add(f);
            }
        }
		return t;
	}

	private void addMisssingHistory(String[] includeArray, Map<String, List<IEndpointStatus>> t) {
		// Some destinations will not have a status history (they are not routinely checked b/c they
        // are for internal testing, so report it as unknown (see IGDD-2156).
        for (String dest: includeArray) {
        	if (!t.containsKey(dest)) {
        		IDestination d = destinationService.findByDestId(dest);
        		if (d != null) {
        			IEndpointStatus ds = endpointStatusService.getEndpointStatus(d);
            		t.put(dest, Collections.singletonList(ds));
        		}
        	}
        }
	}
    
    @Operation(summary = "Get the status history for a destination",
            description = "Returns the status history of the destination")
    @ApiResponse(responseCode = "200", description = "Success",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation= EndpointStatus.class)
            )
    )
    @GetMapping("/statushistory/{id}")
	public List<IEndpointStatus> searchStatusHistoryById(
			@PathVariable String id,
			@RequestParam(name = "count", defaultValue = "1") int count) {
		if (destinationService.findByDestId(id) == null) {
			throw new ResourceNotFoundException(String.format("Destination %s does not exist", id));
		}
		Map<String, List<IEndpointStatus>> t = searchStatusHistory(id, null, count);
		return t.get(id);
	}

	/**
	 * Delete a status history. Status history is updated as servers are connected
	 * to by the status checker. Deleting the statushistory deletes the most
	 * recently stored history value from the local server. If the server is NOT
	 * connecting because of a circuit-breaker failure, this will clear the failure
	 * until the next time the circuit breaker is thrown.
	 * 
	 * @param id          The destination whose history should be removed.
	 */
    @Operation(summary = "Delete the status history for a destination",
            description = "Delete the status history of the destination")
    @ApiResponse(responseCode = "204", description = "Success", content = @Content)
	@DeleteMapping("/statushistory/{id}")
	@ResponseStatus(value = HttpStatus.NO_CONTENT)
	@RolesAllowed(Roles.ADMIN)
	public void deleteStatusHistory(@PathVariable String id) {
		if (destinationService.findByDestId(id) == null) {
			throw new ResourceNotFoundException(String.format("Destination %s does not exist", id));
		}
		endpointStatusService.removeById(id);
	}
    
    @GetMapping("/status")
    @Operation(summary = "Get the status for all destinations", 
    	description = "Check the status of all destinations")
    @ApiResponse(responseCode = "200", description = "Success", 
   		content = @Content(mediaType = "application/json",
   			schema = @Schema(implementation=EndpointStatus.Map.class))
  	)
	public Map<String, IEndpointStatus> getStatus() {
    	destinationService.refresh();
		List<IDestination> l = destinationService.getAllDestinations();
		Map<String, IEndpointStatus> l2 = new TreeMap<>();
		ExecUtils.execAll(l, dest -> l2.put(dest.getDestId(),
				checkerService.checkDestination(dest)), 2, TimeUnit.MINUTES);
		return l2;
	}

	
	@GetMapping("/status/{id}")
    @Operation(summary = "Get the status for the destination", 
		description = "Check the status of the destination")
	@ApiResponse(responseCode = "200", description = "Success", 
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation=EndpointStatus.class))
	)
	public IEndpointStatus getStatusById(@PathVariable String id) {
		IDestination d = destinationService.findByDestId(id);
		if (d == null) {
			throw new ResourceNotFoundException(String.format("Destination %s not found", id));
		}
		checkerService.checkDestination(d);
		return checkerService.updateDestinationStatus(d);
	}
	
	/**
	 * Response for a reset of every destination.
	 *
	 * @param destinations	The status of each destination on this instance
	 * @param hosts	The outcome for each instance, keyed {@code region:hostname}
	 * @param regions	One status per region: {@code complete}, {@code partial} or {@code failed}
	 */
	public record ResetAllResponse(
			Map<String, IEndpointStatus> destinations,
			Map<String, String> hosts,
			Map<String, String> regions) {
	}

	/**
	 * Response for a reset of one destination.
	 * <p>
	 * The status fields are unwrapped to the top level, so every field this endpoint
	 * returned before keeps its name and meaning. See IGDD-3599, design decision D5.
	 * </p>
	 */
	public static class ResetOneResponse {
		@JsonUnwrapped
		private final IEndpointStatus status;
		private final Map<String, String> hosts;
		private final Map<String, String> regions;

		ResetOneResponse(IEndpointStatus status, Map<String, String> hosts, Map<String, String> regions) {
			this.status = status;
			this.hosts = hosts;
			this.regions = regions;
		}

		/** @return The destination status, serialized at the top level */
		public IEndpointStatus getStatus() {
			return status;
		}

		/** @return The outcome for each instance, keyed {@code region:hostname} */
		public Map<String, String> getHosts() {
			return hosts;
		}

		/** @return One status per region */
		public Map<String, String> getRegions() {
			return regions;
		}
	}

	@PostMapping("/reset")
	@RolesAllowed(Roles.ADMIN)
	@Operation(summary = "Reset the circuit breakers on every instance",
			description = "Reset the circuit breaker for all destinations on every Hub instance "
					+ "in every region. Returns 200 even when an instance fails; read the hosts "
					+ "and regions members for the outcome.")
	@ApiResponse(responseCode = "200", description = "Success",
	content = @Content(mediaType = "application/json",
		schema = @Schema(implementation=ResetAllResponse.class))
	)
	public ResetAllResponse resetCircuitBreakers() {
		Map<String, String> hosts = applyEverywhere(null);
		Map<String, IEndpointStatus> l2 = new TreeMap<>();
		endpointStatusService.findAll().forEach(s -> l2.put(s.getDestId(), s));
		return new ResetAllResponse(l2, hosts, RefreshQueueService.summarizeByRegion(hosts));
	}

	@PostMapping("/reset/{id}")
	@Operation(summary = "Reset the circuit breaker for a single destination on every instance",
			description = "Reset the circuit breaker for the specified destination on every Hub "
					+ "instance in every region. Returns 200 even when an instance fails; read the "
					+ "hosts and regions members for the outcome.")
	@ApiResponse(responseCode = "200", description = "Success",
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ResetOneResponse.class))
	)
	@RolesAllowed(Roles.ADMIN)
	public ResetOneResponse resetCircuitBreakerById(@PathVariable String id) {
		if (destinationService.findByDestId(id) == null) {
			throw new ResourceNotFoundException(String.format("Destination %s not found", id));
		}
		IEndpointStatus status = endpointStatusService.resetCircuitBreakerById(id);
		if (status == null) {
			throw new ResourceNotFoundException(String.format("No status found for destination %s", id));
		}
		Map<String, String> hosts = applyEverywhere(id);
		return new ResetOneResponse(status, hosts, RefreshQueueService.summarizeByRegion(hosts));
	}

	/**
	 * Send the reset to every other instance and collect the outcome.
	 * <p>
	 * The local reset already happened before this runs. Only the remote instances are
	 * messaged, and the local outcome is recorded directly. Commands travel over SQS,
	 * never by calling this endpoint again. See IGDD-3599, design decision D4.
	 * </p>
	 *
	 * @param destId	The destination to reset, or null for every destination
	 * @return The outcome for each instance, keyed {@code region:hostname}
	 */
	private Map<String, String> applyEverywhere(String destId) {
		String me = SystemUtils.getHostname();
		String myRegion = RefreshQueueService.getRegion();
		String eventId = MDC.get(EventId.EVENTID_KEY);
		Map<String, String> hosts = new TreeMap<>();
		hosts.put(myRegion + ":" + me, "OK (Local)");
		refreshQueueService.distribute(
				new RefreshRequest(true, eventId, me, myRegion, null, false, destId), hosts);
		return hosts;
	}
}
