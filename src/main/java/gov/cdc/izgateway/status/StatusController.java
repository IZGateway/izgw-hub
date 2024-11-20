package gov.cdc.izgateway.status;

import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.db.model.EndpointStatus;
import gov.cdc.izgateway.db.service.DestinationService;
import gov.cdc.izgateway.db.service.StatusCheckerService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.repository.EndpointStatusRepository;
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
    @Autowired
    public StatusController(EndpointStatusService endpointStatusService, StatusCheckerService checkerService, AccessControlRegistry registry, DestinationService destinationService) {
        this.endpointStatusService = endpointStatusService;
        this.checkerService = checkerService;
        this.destinationService = destinationService;
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
        // Sort the map to make it easier to work with, otherwise it returns in hash
        // order, which is garbage.
        String[] includeArray = include == null ? EndpointStatusRepository.INCLUDE_ALL : include.split("[\\s,]+");
        List<String> included = Arrays.asList(includeArray);
        List<? extends IEndpointStatus> found = endpointStatusService.find(count, includeArray);

        Map<String, List<IEndpointStatus>> t = new TreeMap<>();
        for (IEndpointStatus f : found) {
            if (included.isEmpty() || included.contains(f.getDestId())) {
                // To check for history values of a certain status, specify ?status=<DesiredStatus> in the URL
                // To check for history values of not having a certain status, specifiy ?status=!<Desired Status> in the URL
            	// This enables quick checks for those endpoints with a status of other than connected.
                if (!StringUtils.isEmpty(status)) {
                	boolean test = !status.startsWith("!");
                	if (StringUtils.equalsIgnoreCase(f.getStatus(), status) != test) {
                		continue;
                	}
                }
                List<IEndpointStatus> l = t.computeIfAbsent(f.getDestId(), k -> new ArrayList<>());
                l.add(f);
            }
        }
        return t;
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
	 * @param servletResp
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

A}
