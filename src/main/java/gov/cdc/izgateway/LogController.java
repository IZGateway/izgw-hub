package gov.cdc.izgateway;

import gov.cdc.izgateway.logging.LogstashMessageSerializer;
import gov.cdc.izgateway.logging.MemoryAppender;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.LogEvent;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.utils.ListConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The LogController provides access to in memory logging data on a server.
 * This is used for integration testing to verify log content is as expected
 * when sending messages.
 */

@RestController
@CrossOrigin
// TODO: Presently, blacklisted users are allowed to access the logs request, b/c blacklisting only
// applies to the SOAP Stack.  Once we apply it to the full HTTP stack, we will have to provide
// SECURE mechanism for clearing the blacklisted state of the testing user.  It cannot be said
// to have been applied to the full stack until this loophole is resolved.
@RolesAllowed({ Roles.ADMIN, Roles.OPERATIONS, Roles.BLACKLIST })
@RequestMapping({"/rest"})
@Lazy(false)
public class LogController implements InitializingBean {
	@Configuration(proxyBeanMethods = false) 
	public static class LogControllerConfig {
	    @Bean
	    public ObjectMapper getObjectMapper() {
	        ObjectMapper mapper = new ObjectMapper();
	        SimpleModule simpleModule = new SimpleModule();
	        simpleModule.addSerializer(ILoggingEvent.class, new LogstashMessageSerializer());
	        mapper.registerModule(simpleModule);
	        return mapper;
		}
	}
	
	private MemoryAppender logData = null;
	private final IDestinationService destinationService;
	private final EndpointStatusService endpointStatusService;
	private final IAccessControlService accessControlService;

	@Autowired
	public LogController(AccessControlRegistry registry, IDestinationService destinationService, EndpointStatusService endpointStatusService, IAccessControlService accessControlService) {
		registry.register(this);
		this.destinationService = destinationService;
		this.endpointStatusService = endpointStatusService;
		this.accessControlService = accessControlService;
	}
	@Override
	public void afterPropertiesSet() throws Exception {
		logData = MemoryAppender.getInstance("memory");
	}

	// TODO: Presently, blacklisted users are allowed to access the logs request, b/c blacklisting only
	// applies to the SOAP Stack.  Once we apply it to the full HTTP stack, we will have to provide
	// SECURE mechanism to clearing the state.
	@Operation(summary = "Get the most recent log records",
			description = "Search for the log records matching the search parameter or all records if there is no search value")
	@ApiResponse(responseCode = "200", description = "Success", 
    	content = { 
    		@Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = LogEvent.class))) 
    	}
	)
	@GetMapping("/logs")
	public List<LogEvent> getLogs(
			@Parameter(description = "The search string", required = false)
			@RequestParam(required = false) String search, 
			HttpServletResponse resp) {

		List<ILoggingEvent> events = null;
		if (logData == null) {
			events = Collections.emptyList();
		} else if (StringUtils.isBlank(search)) {
			events = logData.getLoggedEvents();
		} else {
			events = logData.search(search);
		}
		
		return new ListConverter<>(events, LogEvent::new);
	}

	@Operation(summary = "Clear log records")
	@ApiResponse(responseCode = "204", description = "Reset the logs", content = @Content)
	@DeleteMapping("/logs")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteLogs(HttpServletRequest servletReq,
			@Parameter(required = false, description="If true, reset the specified endpoint, clearing maintenance")
			@RequestParam(required = false) String clear) {
		if (logData != null) {
			logData.reset();
		}
		if (clear != null) {
			// Clear any errors from the specified destination.
			// This allows testing to reset both logs and a destination before a new test
			IEndpointStatus status = endpointStatusService.findById(clear);
			if (status != null) {
				status.setStatus(IEndpointStatus.CONNECTED);
				clearMaintenance(clear);
			}
			accessControlService.removeUserFromBlacklist(RequestContext.getSourceInfo().getCommonName());
		}
	}
	
	private void clearMaintenance(String destId) {
		IDestination dest = destinationService.findByDestId(destId);
		if (dest != null) {
			destinationService.clearMaintenance(dest);
		}
	}
}
