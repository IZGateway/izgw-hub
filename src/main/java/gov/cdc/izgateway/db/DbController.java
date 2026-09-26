package gov.cdc.izgateway.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import gov.cdc.izgateway.common.BadRequestException;
import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.configuration.DynamoDbConfig;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.ICertificateStatus;
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

import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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
	private static final String REGION = Objects.toString(System.getenv("AWS_REGION"), "unknown");

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
		private final ICertificateStatusService<ICertificateStatus> certificateStatusService;
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
				final ICertificateStatusService<ICertificateStatus> certificateStatusService
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
	private final ApiKeyPrincipalProvider apiKeyPrincipalProvider;
	/** Cached region for THIS host */
	private final RefreshQueueService refreshQueueService;
	private final DynamoDbClient ddbClient;
	private final String tableName;

	// Local instance rather than an autowired bean: LogController already defines its own
	// specialized ObjectMapper @Bean (Logstash serialization), and this endpoint's raw-item-to-
	// JSON conversion has no need to share it -- matches ADSController's existing local
	// `new ObjectMapper()` precedent for ad hoc JSON handling.
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * Entity types servable via {@link #getDataByType(String)}/{@link #getDataByTypeAndKey(String, String)}.
	 * An allowlist rather than a denylist of excluded types, so a new entity type introduced later
	 * (in either this codebase or izg-configuration-console, which shares this table) is 404'd by
	 * default instead of silently becoming servable.
	 *
	 * <p>Includes the 13 types with a Java model in this codebase, plus three written directly by
	 * izg-configuration-console's TypeScript code with no Java model at all ({@code ApiKeyDomain},
	 * {@code ApiKeyDomainOwner}, {@code Sender}) -- these are exactly the entity types this endpoint
	 * exists to make inspectable, since the migration-verification test harness in
	 * {@code izgw-db-migration} otherwise has no way to check Hub-side data for them.</p>
	 *
	 * <p>Deliberately excludes every audit/change-request entity type sharing this table
	 * ({@code DestinationChangeRequest}, {@code DestinationAudit}, {@code AllowedUserAudit},
	 * {@code AccessGroupAudit}, {@code DenyListAudit}, {@code AdsFileTypeAudit}) -- these must never
	 * be servable via this endpoint.</p>
	 */
	private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of(
		"AccessControl", "AccessGroup", "AllowedUser", "ApiKeyCredential", "CertificateStatus",
		"Destination", "DenyListRecord", "Event", "EndpointStatus", "FileType", "Jurisdiction",
		"MessageHeader", "OrganizationRecord", "SourceAttackExceptionRecord",
		"ApiKeyDomain", "ApiKeyDomainOwner", "Sender"
	);

	/**
	 * Field names to strip from a raw item before it is returned, keyed by entityType. Extend this
	 * map when a new sensitive field is found, rather than adding new redact-by-type branches.
	 *
	 * <p>{@code Destination.password} is the only field here that is actually secret, and is
	 * redacted for that reason. {@code Destination.username} and {@code Destination.passExpiry}
	 * are NOT secrets, but are redacted anyway purely for consistency with the rest of this
	 * codebase's existing treatment of the three as a set ({@code AbstractDestination.maskCredentials()}
	 * nulls all three; {@code AbstractDestination} marks all three {@code @JsonIgnore}) -- this
	 * endpoint bypasses Jackson bean serialization entirely (raw {@code AttributeValue} ->
	 * {@code EnhancedDocument.toJson()}), so those existing protections don't apply here on their
	 * own. {@code ApiKeyDomain.challengeUuid} is also redacted, and this one IS a secret: it is the
	 * DNS TXT domain-ownership challenge token issued while a domain is {@code pending_challenge},
	 * and leaking it would let someone who does not own the domain complete that domain's ownership
	 * verification.</p>
	 */
	private static final Map<String, Set<String>> REDACTED_FIELDS = Map.of(
		"Destination", Set.of("password", "username", "passExpiry"),
		"ApiKeyDomain", Set.of("challengeUuid")
	);

	/**
	 * Construct a new DBController class.
	 *
	 * @param hostService	The service use to access running hosts
	 * @param config	The configuration providing access to db services
	 * @param registry	The access control registry managing these APIs
	 * @param apiKeyPrincipalProvider  The API key principal provider for credential cache eviction
	 * @param ddbClient	The raw DynamoDB client, used for the generic entity-type read endpoint
	 * @param ddbConfig	The DynamoDB configuration, used to resolve the table name
	 */
	@Autowired
	public DbController(
		IHostRepository hostService,
		DbControllerConfiguration config,
		AccessControlRegistry registry,
		ApiKeyPrincipalProvider apiKeyPrincipalProvider,
		DynamoDbClient ddbClient,
		DynamoDbConfig ddbConfig
	) {
		this.hostService = hostService;
		this.configuration = config;
		this.apiKeyPrincipalProvider = apiKeyPrincipalProvider;
		this.refreshQueueService = new RefreshQueueService(REGION, this, apiKeyPrincipalProvider);
		this.ddbClient = ddbClient;
		this.tableName = ddbConfig.getDynamodbTable();
		registry.register(this);
	}
	
	protected void refresh() {
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
			@SuppressWarnings("unused")
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
	     schema = @Schema(implementation = IDestination.Map.class))
	)
	@ApiResponse(responseCode = "400", description = "The identifier cannot be changed.", 
		content = @Content)
	@GetMapping("/config")
	public IDestination.Map getConfig() {
		refresh();
		List<IDestination> l = configuration.getDestinationService().getAllDestinations();
		IDestination.Map l2 = new Destination.Map();
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
      @RequestParam(name = "reset", defaultValue = "false") boolean reset,
      @Parameter(description = "If provided, evict this specific API key jti from the credential cache on all instances.", required = false)
      @RequestParam(name = "jti", required = false) String jti
	) {
      HostMap results = new HostMap();
      if (jti != null) {
          apiKeyPrincipalProvider.evictCredential(jti);
      }
      refresh();
      String me = SystemUtils.getHostname();
      String eventId = MDC.get(EventId.EVENTID_KEY);
      results.put(REGION + ":" + me, "OK (Local)");
      if (reset) {
         resetEndpoint(me, eventId);
      }
      if ("true".equalsIgnoreCase(all)) {
         Map<String, String> hostsAndRegions = hostService.getHostsAndRegion();
         for (Map.Entry<String, String> entry : hostsAndRegions.entrySet()) {
            String hostName = entry.getKey();
            String hostRegion = entry.getValue();
            if (hostName.equalsIgnoreCase(me) && hostRegion.equals(REGION)) {
			   continue;  // Yeah, we already did that one
			}
            RefreshRequest request = new RefreshRequest(reset, eventId, me, REGION, jti);
            refreshQueueService.sendRefreshMessage(request, results, hostName, hostRegion);
         }
         refreshQueueService.awaitRefreshResponses(eventId, results);
      }
      return results;
    }

	protected String resetEndpoint(String host, String eventId) {
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

	@Operation(summary = "Report the running host instances and their regions.",
			description = "Refresh the list of running hosts.  Returns a list region:hostname values.")
	@ApiResponse(responseCode = "200", description = "a list region:hostname values", 
	    content = @Content(
	    		mediaType = "application/json", 
	    		array = @ArraySchema(schema = @Schema(implementation = String.class))
	    )
	)
	@GetMapping("/hosts")
	public List<String> getRunningHosts2(
		@Parameter(required=false, 
			description="If true, return only locally accessible hosts, if false, return only non-locally accessible hosts, if omitted return all hosts.")
		@RequestParam(name = "local", required = false) Boolean local) {
		
		Map<String, String> m = hostService.getHostsAndRegion();

		for (Iterator<Map.Entry<String, String>> i = m.entrySet().iterator(); i.hasNext();) {
			filterHosts(local, i); 
		}
		if (Boolean.FALSE.equals(local)) {
			// Remove this server 
			m.remove(SystemUtils.getHostname());
		} else {
			// Include this server (overwrites data from repository with locally known data) 
			m.put(SystemUtils.getHostname(), REGION);
		}
		return m.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList();
	}

	/**
	 * Filter hosts based on local parameter
	 * @param local If true, keep only hosts in the same region, if false, keep only hosts in other regions, if null, keep all
	 * @param i The iterator to filter out entries from
	 */
	private void filterHosts(Boolean local, Iterator<Map.Entry<String, String>> i) {
		Map.Entry<String, String> e = i.next();
		if (e.getValue() == null || e.getValue().isEmpty()) {
			i.remove();
		} else if (Boolean.TRUE.equals(local)) {
			try {
				// This will throw an exception if not locally reachable
				InetAddress.getAllByName(e.getKey());
				// If the region doesn't match ours, remove it
				if (!REGION.equals(e.getValue())) {
					i.remove();
				}
			} catch (Exception ex) {
				// Log and remove it
				log.warn(Markers2.append(ex), "Could not resolve host {}: {}", e.getKey(), ex.getMessage());
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
	    		schema = @Schema(implementation = IDestination.Map.class)
	    )
	)
	@GetMapping("/maint")
	public IDestination.Map getMaintenance() {
		IDestination.Map result = new Destination.Map();
		IDestination.Map m = getConfig();

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
		Date startDate = ObjectUtils.getIfNull(getDateParameter(start, "Start"), now);
		start = String.format("%tc", startDate);
		Date endDate;
		if ("none".equalsIgnoreCase(end)) {
			endDate = null;
		} else {
			// If end not present, use default maintenance duration
			endDate = ObjectUtils.getIfNull(getDateParameter(end, "End"), new Date(startDate.getTime() + DEFAULT_MAINT_PERIOD));
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
		getRefreshed("true", false, null);
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
		// Refresh other services.
		getRefreshed("true", false, null);
		return getConfigById(id);
	}

	private ResourceNotFoundException destinationNotFound(String id) {
		return notFound("Destination", id);
	}

	private ResourceNotFoundException notFound(String what, String id) {
		return new ResourceNotFoundException(String.format("%s %s not found.", what, id));
	}

	@Operation(summary = "Report all entities of the specified type",
			description = "Returns every DynamoDB item of the given entity type, keyed by sortKey. "
					+ "Only entity types in a fixed allowlist are servable; audit/change-request "
					+ "entity types are never returned. Destination items never include "
					+ "password/username/passExpiry.")
	@ApiResponse(responseCode = "200", description = "A map of sortKey to the item at that key.",
		content = @Content(mediaType = "application/json"))
	@ApiResponse(responseCode = "404", description = "The entity type is unknown or not servable via this API.",
		content = @Content)
	@GetMapping("/data/{entityType}")
	@RolesAllowed(Roles.ADMIN)
	public Map<String, Object> getDataByType(
			@Schema(description = "The DynamoDB entity type to retrieve, e.g. AllowedUser or ApiKeyDomain")
			@PathVariable String entityType) {
		requireAllowedType(entityType);
		Map<String, Object> result = new LinkedHashMap<>();
		QueryRequest request = QueryRequest.builder()
				.tableName(tableName)
				.keyConditionExpression("entityType = :et")
				.expressionAttributeValues(Map.of(":et", AttributeValue.fromS(entityType)))
				.build();
		// queryPaginator transparently walks every LastEvaluatedKey continuation -- no manual
		// pagination loop needed (see ApiKeyCredentialRepository#findAll for the same pattern).
		for (QueryResponse page : ddbClient.queryPaginator(request)) {
			for (Map<String, AttributeValue> item : page.items()) {
				result.put(item.get("sortKey").s(), toJsonObject(redact(entityType, item)));
			}
		}
		return result;
	}

	// Almost every real sortKey in this table is "#"-delimited (e.g. Destination's
	// "{destTypeId}#{destId}", AllowedUser's "{environment}#{destinationId}#{principal}"), and an
	// unencoded "#" in a URL path starts a fragment rather than being sent to the server, so a
	// caller sends ":" in its place and this substitutes it back before building the DynamoDB key.
	// Chosen over "_"/"-" because those legitimately appear inside real key field values (e.g.
	// domain names); ":" does not collide with anything currently stored.
	private static final char SORT_KEY_PATH_SUBSTITUTE = ':';
	private static final char SORT_KEY_REAL_DELIMITER = '#';

	@Operation(summary = "Report the specified entity",
			description = "Returns the single DynamoDB item for the given entity type and sortKey. "
					+ "Only entity types in a fixed allowlist are servable; audit/change-request "
					+ "entity types are never returned. A Destination item never includes "
					+ "password/username/passExpiry.")
	@ApiResponse(responseCode = "200", description = "The item at the specified entity type and sortKey.",
		content = @Content(mediaType = "application/json"))
	@ApiResponse(responseCode = "404", description = "The entity type is unknown/not servable, "
			+ "or no item exists at that sortKey.", content = @Content)
	@GetMapping("/data/{entityType}/{sortKey}")
	@RolesAllowed(Roles.ADMIN)
	public Object getDataByTypeAndKey(
			@Schema(description = "The DynamoDB entity type to retrieve, e.g. AllowedUser or ApiKeyDomain")
			@PathVariable String entityType,
			@Schema(description = "The sortKey of the specific item to retrieve. Most sortKeys are "
					+ "\"#\"-delimited (e.g. \"2#dev\"), but \"#\" cannot appear literally in a URL "
					+ "path -- send \":\" in its place (e.g. \"2:dev\"); it is substituted back to "
					+ "\"#\" before querying.")
			@PathVariable String sortKey) {
		requireAllowedType(entityType);
		String realSortKey = sortKey.replace(SORT_KEY_PATH_SUBSTITUTE, SORT_KEY_REAL_DELIMITER);
		GetItemResponse resp = ddbClient.getItem(GetItemRequest.builder()
				.tableName(tableName)
				.key(Map.of(
						"entityType", AttributeValue.fromS(entityType),
						"sortKey", AttributeValue.fromS(realSortKey)))
				.build());
		if (!resp.hasItem()) {
			throw notFound(entityType, sortKey);
		}
		return toJsonObject(redact(entityType, resp.item()));
	}

	private void requireAllowedType(String entityType) {
		if (!ALLOWED_ENTITY_TYPES.contains(entityType)) {
			// Deliberately the same ResourceNotFoundException (404) used for a missing sortKey,
			// rather than a 403 -- this avoids revealing which entity types are being deliberately
			// hidden (e.g. the audit/change-request types) to a caller probing the endpoint.
			throw new ResourceNotFoundException("Unknown or restricted entity type: " + entityType);
		}
	}

	private Map<String, AttributeValue> redact(String entityType, Map<String, AttributeValue> item) {
		Set<String> fields = REDACTED_FIELDS.get(entityType);
		if (fields == null) {
			return item;
		}
		Map<String, AttributeValue> copy = new HashMap<>(item);
		fields.forEach(copy::remove);
		return copy;
	}

	// Converts a raw DynamoDB item straight to a JSON-compatible Object (LinkedHashMap/List/
	// primitives) via the SDK's own EnhancedDocument -- no per-entity-type Java model needed, which
	// is what lets this endpoint serve types (ApiKeyDomain, ApiKeyDomainOwner, Sender) that have no
	// Java model in this codebase at all.
	private Object toJsonObject(Map<String, AttributeValue> item) {
		try {
			return OBJECT_MAPPER.readValue(EnhancedDocument.fromAttributeValueMap(item).toJson(), Object.class);
		} catch (IOException e) {
			// EnhancedDocument.toJson() always produces valid JSON for a valid AttributeValue map,
			// so this is not expected in practice; fails the request rather than returning a
			// silently-truncated or malformed body.
			throw new IllegalStateException("Failed to convert DynamoDB item to JSON", e);
		}
	}

}
