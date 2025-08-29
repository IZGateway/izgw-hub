package gov.cdc.izgateway.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.annotation.security.RolesAllowed;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.cdc.izgateway.common.BadRequestException;
import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.db.model.Destination;
import gov.cdc.izgateway.db.model.MessageHeader;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.service.ICertificateStatusService;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.utils.DateUtil;
import gov.cdc.izgateway.utils.SystemUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import javax.net.ssl.HttpsURLConnection;

/**
 * This class provides APIs to manage information in the database.
 * 
 * @author Audacious Inquiry
 *
 */
@Slf4j
@RestController
@CrossOrigin
@RolesAllowed({ Roles.ADMIN, Roles.OPERATIONS })
@RequestMapping({ "/rest"})
@Lazy(false)
public class DbController {
	private static final long DEFAULT_MAINT_PERIOD = TimeUnit.MINUTES.toMillis(30);
	/** Cached list of ingress addresses for THIS host */
	private List<String> ingressAddresses = null;
	/**
	 * Configuration for the DB Controller.
	 * 
	 * @author Audacious Inquiry
	 */
	@Configuration
	@Getter
	public static class DbControllerConfiguration {
		private final IMessageHeaderService messageHeaderService;
		private final IDestinationService destinationService;
		private final IAccessControlService accessControlService;
		private final IJurisdictionService jurisdictionService;
		private final ICertificateStatusService certificateStatusService;
		/**
		 * Construct a new DB Controller Configuration
		 * 
		 * @param messageHeaderService	The service supporting message headers
		 * @param destinationService	The service supporting with destinations
		 * @param accessControlService	The service supporting access controls
		 * @param jurisdictionService	The service supporting jurisdictions
		 * @param certificateStatusService	The service supporting certificate status
		 */
		@Autowired
		public DbControllerConfiguration(
				final IMessageHeaderService messageHeaderService,
				final IDestinationService destinationService,
				final IAccessControlService accessControlService,
				final IJurisdictionService jurisdictionService,
				final ICertificateStatusService certificateStatusService
				) {
			this.messageHeaderService = messageHeaderService;
			this.destinationService = destinationService;
			this.accessControlService = accessControlService;
			this.jurisdictionService = jurisdictionService;
			this.certificateStatusService = certificateStatusService;
		}
		
		private void refresh() {
			this.messageHeaderService.refresh();
			this.destinationService.refresh();
			this.accessControlService.refresh();
			this.jurisdictionService.refresh();
			this.certificateStatusService.refresh();
		}
	}
	private final IHostRepository hostService;
	private final DbControllerConfiguration configuration;

	/**
	 * Construct a new DBController class.
	 * 
	 * @param hostService	The service use to access running hosts
	 * @param config	The configuration providing access to db services
	 * @param registry	The access control registry managing these APIs
	 */
	@Autowired
	public DbController(
		IHostRepository hostService,
		DbControllerConfiguration config,
		AccessControlRegistry registry
	) {
		this.hostService = hostService;
		this.configuration = config; 
		registry.register(this);
	}
	
	private void refresh() {
		configuration.refresh();
	}
	/**
	 * Report on content in the MessageHeader records in the system configuration.
	 * 
	 * @param include	The list of headers headers to match
	 * @return	A map containing the header information.
	 */
	@Operation(summary = "Get Message Header Info entries",
			description = "Returns the Message Header Values for HL7 Message for all endpoints")
	@ApiResponse(responseCode = "200", description = "The Message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = MessageHeader.Map.class))
	)
	@GetMapping("/headers")
	public IMessageHeader.Map getMessageHeaders(@RequestParam(defaultValue = "") String include) {
		IMessageHeader.Map result = new IMessageHeader.Map();
		Stream<IMessageHeader> all = configuration.getMessageHeaderService().getAllMessageHeaders().stream();
		if (!StringUtils.isEmpty(include)) {
			List<String> includes = Arrays.asList(include.split("[\\s,;]+"));
			all = all.filter(h -> includes.contains(h.getMsh()));
		}
		all.forEach(h -> result.put(h.getMsh(), h));
		return result;
	}

	/**
	 * Get Message Header Info entry for a given endpoint
	 * 
	 * @param id The MSH3 or MSH4 value to retrieve an entry for
	 * @return	The message header record for the specified MSH value.
	 */
	@Operation(summary = "Get Message Header Info entry for a given endpoint",
			description = "Returns the Message Header Values for HL7 Message for the given MSH3 or MSH4 value")
	@ApiResponse(responseCode = "200", description = "The Message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = MessageHeader.class))
	)
	@ApiResponse(responseCode = "404", description = "No MSH3 or MSH4 entry exists for the specified value", 
		content = @Content)
	@GetMapping("/headers/{id}")
	public IMessageHeader getMessageHeadersById(
			@Schema(description = "The MSH3 or MSH4 value to retrieve an entry for")
			@PathVariable String id) {
		IMessageHeader.Map l = getMessageHeaders(id);
		if (l.isEmpty() || !l.containsKey(id)) {
			throw new ResourceNotFoundException("Resource " + id + " not found");
		}
		return l.get(id);
	}

	@Operation(summary = "Update the username and password for the specified MSH-3 value",
			description = "Returns the Message Header Values for HL7 Message for the given MSH3 or MSH4 value")
	@ApiResponse(responseCode = "200", description = "The Message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = MessageHeader.class))
	)
	@ApiResponse(responseCode = "400", description = "The identifier cannot be changed.", 
		content = @Content)
	@PostMapping("/headers/{id}")
	public IMessageHeader setMessageHeadersById(@PathVariable String id, @RequestBody MessageHeader newValues) {
		IMessageHeader old;
		old = getMessageHeadersById(id);
		if (!id.equals(newValues.getMsh())) {
			throw new BadRequestException(String.format("Identifier (%s) does not match %s", newValues.getMsh(), id));
		}

		// Copy only what can be changed. This cannot create a new MessageHeaderInfo
		// just yet. That should be reserved for Config Console.
		old.setFacilityId(newValues.getFacilityId());
		old.setUsername(newValues.getUsername());
		old.setPassword(newValues.getPassword());

		return configuration.getMessageHeaderService().saveAndFlush(old);
	}
	
	@Operation(summary = "Delete the header record for the specified MSH-3 value",
			description = "Deletes the Message Header and username password values for HL7 Message for the given MSH3 or MSH4 value")
	@ApiResponse(responseCode = "200", description = "The deleted message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = MessageHeader.class))
	)
	@ApiResponse(responseCode = "404", description = "The header record cannot be found.", content = @Content)
	@DeleteMapping("/headers/{id}")
	public IMessageHeader deleteMessageHeadersById(@PathVariable String id) {
		refresh();
		IMessageHeader old = getMessageHeadersById(id);
		configuration.getMessageHeaderService().delete(id);
		return old;
	}
	
	@Operation(summary = "Create a header mapping, setting the username and password for the specified MSH-3 value",
			description = "Returns the Message Header Values for HL7 Message for the given MSH3 or MSH4 value")
	@ApiResponse(responseCode = "201", description = "The created Message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = MessageHeader.class))
	)
	@ApiResponse(responseCode = "400", description = "The identifier cannot be changed.", content = @Content)
	@PutMapping("/headers")
	@ResponseStatus(HttpStatus.CREATED)
	public IMessageHeader createMessageHeadersById(@RequestBody MessageHeader newValues) {
		try {
			IMessageHeader old = getMessageHeadersById(newValues.getMsh());
			throw new BadRequestException(String.format("A Message Header already exists for %s", newValues.getMsh()));
		} catch (ResourceNotFoundException ignored) {
			// We expect it to be not found.
		} 
		// Don't allow a MessageHeader record to reference a non-existant destination.
		IDestination dest = configuration.getDestinationService().findByDestId(newValues.getDestId());
		if (dest == null) {
			throw new ResourceNotFoundException(String.format("Destination %s does not exist", newValues.getDestId()));
		}
		
		return configuration.getMessageHeaderService().saveAndFlush(newValues);
	}

	@Operation(summary = "Report the configuration for all endpoints",
			description = "Returns configuration for all endpoints")
	@ApiResponse(responseCode = "200", description = "The Message Header information values", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = Destination.Map.class))
	)
	@ApiResponse(responseCode = "400", description = "The identifier cannot be changed.", 
		content = @Content)
	@GetMapping("/config")
	public Destination.Map getConfig() {
		refresh();
		List<IDestination> l = configuration.getDestinationService().getAllDestinations();
		Destination.Map l2 = new Destination.Map();
		l.forEach(d -> l2.put(d.getDestId(), d.safeCopy()));
		return l2;
	}

	@Operation(summary = "Report the configuration for the specified endpoint",
			description = "Returns configuration for the specified endpoint")
	@ApiResponse(responseCode = "200", description = "The endpoint configuration", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = Destination.class))
	)
	@ApiResponse(responseCode = "404", description = "The endpoint does not exist.", 
		content = @Content)
	@GetMapping("/config/{id}")
	public IDestination getConfigById(
			@Schema(description="The endpoint to report the configuration for")
			@PathVariable String id) {
		IDestination d = configuration.getDestinationService().findByDestId(id);
		if (d == null) {
			throw destinationNotFound(id);
		}
		return d.safeCopy();
	}

	@SuppressWarnings("serial")
	private static class HostMap extends TreeMap<String, String> {}
	@Operation(summary = "Refresh host setup from the database",
			description = "Refresh the current or all instances.  Returns refresh status for each instance refreshed.")
	@ApiResponse(responseCode = "200", description = "A map indicating the refresh status for each host.", 
	    content = @Content(mediaType = "application/json", 
	     	schema = @Schema(implementation=HostMap.class)
	    )
	)
	@ApiResponse(responseCode = "404", description = "The endpoint does not exist.", 
		content = @Content)
  	@GetMapping("/refresh")
	@RolesAllowed({ Roles.ADMIN, Roles.INTERNAL })
	public HostMap getRefreshed(
		@Parameter(description = "If true or local, refresh all accessible instances, otherwise refresh only the current instance.", required = false)
		@RequestParam(name = "all", defaultValue = "false") String all,
		@Parameter(description = "If true, reset circuit breakers as well.", required = false)
		@RequestParam(name = "reset", defaultValue = "false") boolean reset

	) {
		HostMap results = new HostMap();
		refresh();
		String me = SystemUtils.getHostname();
		// Send refresh request in parallel to all known endpoints
		String eventId = MDC.get(EventId.EVENTID_KEY);
		
		results.put(me, "OK (Local)");
		if (reset) {
			resetEndpoint(me, eventId);
		} 
		
		if ("true".equalsIgnoreCase(all) || "local".equalsIgnoreCase(all)) {
			refreshLocalEndpoints(reset, results, me, eventId);
		}
		if ("true".equalsIgnoreCase(all)) {
			refreshRegionalEndpoints(reset, results, eventId);
		}
		return results;
	}

	/**
	 * Send a refresh to all local endpoints (those with an IP ingress address that is the same as this host).
	 * @param reset	 If true, reset circuit breakers as well
	 * @param results	The map to add results to
	 * @param me	 The name of this host
	 * @param eventId	The event ID to use in logging
	 */
	private void refreshLocalEndpoints(boolean reset, HostMap results, String me, String eventId) {
		ExecutorService ex = Executors.newFixedThreadPool(4);

		List<String> hosts = getRunningHosts(false);
		for (String host : hosts) {
			// Don't recursively refresh yourself
			if (host.equalsIgnoreCase(me)) {
				continue;
			}
			ex.execute(() -> results.put(host, refreshEndpoint(host, eventId)));
			if (reset) {
				ex.execute(() -> resetEndpoint(host, eventId));
			}
		}

		ex.shutdown();
		try {
			ex.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// Log status for any not done due to timeout
		for (String host : hosts) {
			results.computeIfAbsent(host, k -> "Timed out");
		}
	}
	
	/**
	 * Send a refresh to all regional endpoints (those with an IP ingress address that differs from this host).
	 * @param reset	 If true, reset circuit breakers as well
	 * @param results	The map to add results to
	 * @param eventId	The event ID to use in logging
	 */
	private void refreshRegionalEndpoints(boolean reset, HostMap results, String eventId) {
		Map<String, List<String>> hosts = getRunningHosts2(false);
		ExecutorService ex = Executors.newFixedThreadPool(2);
		List<String> remoteHosts = new ArrayList<>();
		for (List<String> values : hosts.values()) {
			if (values.stream().anyMatch(addr -> ingressAddresses.contains(addr))) {
				// This is the same as the local host ingress, skip it.  We shouldn't get these, but just in case.
				// and we don't want to get into a recursive loop.
				continue;
			}
			String host = values.get(0);
			remoteHosts.add(host);
			ex.execute(() -> results.put(host, refreshLocalEndpoint(host, reset, eventId)));
		}
		ex.shutdown();
		try {
			ex.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// Log status for any not done due to timeout
		for (String host : remoteHosts) {
			results.computeIfAbsent(host, k -> "Timed out");
		}
	}

	private String refreshLocalEndpoint(String host, boolean reset, String eventId) {
		return callEndpoint(host, eventId, "/rest/refresh?all=local" + (reset ? "&reset=true" : ""));
	}
	
	private String refreshEndpoint(String host, String eventId) {
		return callEndpoint(host, eventId, "/rest/refresh");
	}

	private String resetEndpoint(String host, String eventId) {
		return callEndpoint(host, eventId, "/rest/reset");
	}

	private String callEndpoint(String host, String eventId, String path) {
		URL url = null;
		MDC.put(EventId.EVENTID_KEY, eventId);
		try {
			url = new URI("https://" + host + path).toURL();
			HttpsURLConnection con = (HttpsURLConnection) url.openConnection();

			if (con.getResponseCode() != HttpStatus.OK.value()) {
				String error = getErrorStream(con);
				log.warn("Could not call {}{}: {}", host, path, error);
				return con.getResponseCode() + error;
			}
			return "OK";
		} catch (Exception e) {
			log.error(Markers2.append(e), "Exception calling {}{}: {}", host, path, e.getMessage());
			return Objects.toString(e.getMessage(), "NOT OK");
		}
	}

	private String getErrorStream(HttpsURLConnection con) {
		String error = " ";
		try {
			InputStream is = con.getErrorStream();
			if (is != null) {
				error += IOUtils.toString(is, StandardCharsets.UTF_8);
			}
		} catch (IOException ex) {
			// Ignore it
		}
		return error;
	}

	@Operation(summary = "Report the host instances running.",
			description = "Refresh the list of running hosts.  Returns an array of hostnames.")
	@ApiResponse(responseCode = "200", description = "A list identifying running host.", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		array = @ArraySchema(schema = @Schema(implementation = String.class))
	    )
	)
	@GetMapping("/hosts")
	public List<String> getRunningHosts(
		@Parameter(required=false, description="If true, return unfiltered output.")
		@RequestParam(name = "raw", defaultValue = "false") boolean raw) {
		List<String> l = hostService.findAll();
		Iterator<String> i = l.iterator();
		// Filter the output to reachable hosts
		if (!raw) {
			while (i.hasNext()) {
				String host = i.next();
				try {
					InetAddress.getAllByName(host);
				} catch (Exception e) {
					i.remove();
				}
			}
		}
		if (!l.contains(SystemUtils.getHostname())) {
			l.add(SystemUtils.getHostname());
		}
		Collections.sort(l);
		return l;
	}

	@Operation(summary = "Report the running host instances and their ingress IP addresses.",
			description = "Refresh the list of running hosts.  Returns a map of hostnames and ingress IP addresses.")
	@ApiResponse(responseCode = "200", description = "A map of hostnames and ingress IP addresses", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		array = @ArraySchema(schema = @Schema(implementation = String.class))
	    )
	)
	@GetMapping("/hosts2")
	public Map<String, List<String>> getRunningHosts2(
		@Parameter(required=false, 
			description="If true, return only locally accessible hosts, if false, return only non-locally accessible hosts, if omitted return all hosts.")
		@RequestParam(name = "local", required = false) Boolean local) {
		
		Map<String, List<String>> m = hostService.getHostsAndIngressAddresses();
		if (ingressAddresses == null) {
			ingressAddresses = Arrays.asList(HealthService.getHealth().getIngressDnsAddress());
		}
		for (Iterator<Map.Entry<String, List<String>>> i = m.entrySet().iterator(); i.hasNext();) {
			filterHosts(local, i); 
		}
		if (Boolean.FALSE.equals(local)) {
			// Remove this server 
			m.remove(SystemUtils.getHostname());
		} else {
			// Include this server (overwrites data from repository with locally known data) 
			m.put(SystemUtils.getHostname(), ingressAddresses);
		}
		return m;
	}

	/**
	 * Filter hosts based on local parameter
	 * @param local If true, keep only locally reachable hosts, if false, keep only non-locally reachable hosts, if null, keep all
	 * @param i The iterator to filter out entries from
	 */
	private void filterHosts(Boolean local, Iterator<Map.Entry<String, List<String>>> i) {
		Map.Entry<String, List<String>> e = i.next();
		if (e.getValue() == null || e.getValue().isEmpty()) {
			i.remove();
		} else if (Boolean.TRUE.equals(local)) {
			try {
				// This will throw an exception if not locally reachable
				InetAddress.getAllByName(e.getKey());
				// If none of the ingress addresses match, remove it
				if (e.getValue().stream().noneMatch(ipAddress -> ingressAddresses.contains(ipAddress))) {
					i.remove();
				}
			} catch (Exception ex) {
				i.remove();
			}
		} else if (Boolean.FALSE.equals(local)) {
			// Remove any that are locally reachable
			try {
				// This will throw an exception if not locally reachable
				InetAddress.getAllByName(e.getKey());
				i.remove();
			} catch (Exception ex) {
				// Ignore it
			}
		}
	}
	
	/**
	 * Return status of all destinations scheduled for maintenance
	 * 
	 * @return The status of all destinations scheduled for maintenance
	 */
	@Operation(summary = "Report status of all destinations scheduled for maintenance",
			description = "Returns all destinations scheduled for maintenance.")
	@ApiResponse(responseCode = "200", description = "A map indicating the maintenance status for each destination.", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		schema = @Schema(implementation = Destination.Map.class)
	    )
	)
	@GetMapping("/maint")
	public IDestination.Map getMaintenance() {
		Destination.Map result = new Destination.Map();
		Destination.Map m = getConfig();

		for (IDestination d : m.values()) {
			if (!StringUtils.isEmpty(d.getMaintReason())) {
				d = d.safeCopy();
				result.put(d.getDestId(), d);
			}
		}
		return result;
	}

	@Operation(summary = "Report the maintenance status of specified destination",
			description = "Returns maintenance status of the specified destination.")
	@ApiResponse(responseCode = "200", description = "The maintenance status for of the destination.", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		schema = @Schema(implementation = Destination.class)
	    )
	)
	@ApiResponse(responseCode = "404", description = "The destination does not exist", content = @Content)
	@GetMapping("/maint/{id}")
	public IDestination getMaintenance(
		@Parameter(description="The destination to check the maintenance status of")
		@PathVariable String id
	) {
		return getConfigById(id);
	}

	@Operation(summary = "Update the maintenance status of specified destination",
			description = "Updates the maintenance status and returns it for specified destination.")
	@ApiResponse(responseCode = "200", description = "The maintenance status for of the destination.", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		schema = @Schema(implementation = Destination.class)
	    )
	)
	@ApiResponse(responseCode = "404", description = "The destination does not exist", content = @Content)
	@PostMapping("/maint/{id}")
	public IDestination setMaintenance(
			@Parameter(description="The destination to update the maintenance status for")
			@PathVariable String id,
			@Parameter(description = "The requested start timestamp, if omitted, start now.", required = false)
			@RequestParam(required = false) String start, 
			@Parameter(description = "The requested end timestamp, if omitted, end 30 minutes after start."
					+ " To make this unspecified, use end=none.", 
				required = false)
			@RequestParam(required = false) String end,
			@Parameter(description = "The Reason for maintenance", required = true)
			@RequestParam(required = true) String reason) {
		
		// Force refresh to be certain of using latest data.
		configuration.getDestinationService().refresh();
		IDestination dest = configuration.getDestinationService().findByDestId(id);
		if (dest == null) {
			throw new ResourceNotFoundException("Destination " + id + " uknown.");
		}
		Date now = new Date();
		// If start not present, treat it as now
		Date startDate = ObjectUtils.defaultIfNull(getDateParameter(start, "Start"), now);
		start = String.format("%tc", startDate);
		Date endDate;
		if ("none".equalsIgnoreCase(end)) {
			endDate = null;
		} else {
			// If end not present, use default maintenance duration
			endDate = ObjectUtils.defaultIfNull(getDateParameter(end, "End"), new Date(startDate.getTime() + DEFAULT_MAINT_PERIOD));
			end = String.format("%tc", endDate);
		}


		if (StringUtils.isBlank(reason)) {
			throw new IllegalArgumentException("Reason cannot be empty or blank for Maintenance");
		}

		if ("default".equalsIgnoreCase(reason)) {
			reason = IEndpointStatus.UNDER_MAINTENANCE;
		}

		if (endDate != null) {
			if (endDate.before(startDate)) {
				throw new IllegalArgumentException(
						String.format("Start (%s) must be before End (%s) in Maintenance Period", start, end));
			}
			if (endDate.before(now)) {
				throw new IllegalArgumentException(
						String.format("End (%tc) must be after now (%tc) in Maintenance Period", endDate, now));
			}
		}
		dest.setMaintReason(reason);
		dest.setMaintStart(startDate);
		dest.setMaintEnd(endDate);

		configuration.getDestinationService().saveAndFlush(dest);
		// Refresh other services.
		getRefreshed("true", false);
		return getConfigById(id);
	}

	private Date getDateParameter(String start, String name) {
		Date startDate;
		try {
			startDate = StringUtils.isEmpty(start) ? null : DateUtil.parseDate(start);
		} catch (ParseException pex) {
			throw new IllegalArgumentException(String.format("%s (%s) is not a valid value", name, start));
		}
		return startDate;
	}

	@Operation(summary = "Clear the maintenance status of specified destination",
			description = "Clear the maintenance status and returns it for specified destination.")
	@ApiResponse(responseCode = "200", description = "The maintenance status for of the destination.", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		schema = @Schema(implementation = Destination.class)
	    )
	)
	@ApiResponse(responseCode = "404", description = "The destination does not exist", content = @Content)
	@DeleteMapping("/maint/{id}")
	public IDestination clearMaintenance(
		@Parameter(description="The destination to clear the maintenance status for")
		@PathVariable String id
	) {
		// Force refresh to be certain of using latest data.
		configuration.getDestinationService().refresh();
		IDestination dest = configuration.getDestinationService().findByDestId(id);
		if (dest == null) {
			throw new ResourceNotFoundException("Destination " + id + " uknown.");
		}
		dest.setMaintReason(null);
		dest.setMaintStart(null);
		dest.setMaintEnd(null);
		configuration.getDestinationService().saveAndFlush(dest);
		return getConfigById(id);
	}

	private ResourceNotFoundException destinationNotFound(String id) {
		return notFound("Destination", id);
	}

	private ResourceNotFoundException notFound(String what, String id) {
		return new ResourceNotFoundException(String.format("%s %s not found.", what, id));
	}

}
