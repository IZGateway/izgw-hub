package gov.cdc.izgateway;

import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.info.HostInfo;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.utils.UtilizationService;
import gov.cdc.izgateway.utils.UtilizationService.Utilization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;

@RestController
@CrossOrigin
@RolesAllowed({Roles.OPEN, Roles.ADMIN})
@RequestMapping({"/rest"})
@Lazy(false)
public class AppController {
	public AppController(AccessControlRegistry registry) {
		registry.register(this);
	}
	@Operation(summary = "Get build information about the application",
			description = "Returns the build number, build machine and build date.")
	  @ApiResponse(responseCode = "200", description = "Success", 
	    content = @Content(mediaType = "text/plain")
	)
	@GetMapping({"/build", "/build.txt"})
	public String getBuild() {
		return Application.getPage(Application.BUILD);
	}

	@Operation(summary = "Get the application logo (favicon)",
			description = "Returns the icon useed as the application logo.")
		@ApiResponse(responseCode = "200", description = "Success", 
	    	content = @Content(mediaType = "image/ico")
	)
	@GetMapping("/logo")
	public String getLogo() {
		return Application.getPage(Application.LOGO);
	}
	
	@Operation(summary = "Get the IP address and hostname of the caller",
			description = "Returns the IP address and hostname of the caller as seen by the application")
	  	@ApiResponse(responseCode = "200", description = "Success", 
	  		content = @Content(mediaType = "application/json",
	  			schema = @Schema(implementation=HostInfo.class)
	  	)
	)
	@GetMapping("/icanhazip")
	public HostInfo getIpAddress(HttpServletRequest req) {
		return new HostInfo(req.getRemoteAddr(), req.getRemoteHost());
	}
	
	/**
	 * Report the health of the application, returning 200 OK and the health status.
	 * @param resp	The response.
	 * @return The Health of the application.
	 */
	@Operation(summary = "Get the health status of the application",
			description = "Returns the health status of the application")
	@ApiResponse(responseCode = "200", description = "Success", 
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation=Health.class))
	)
	@GetMapping("/health")
	public Health getHealth() {
		return HealthService.getHealth();
	}
	
	@Operation(summary = "Get application resource utilization",
			description = "Returns metrics on memory and CPU performance")
	@ApiResponse(responseCode = "200", description = "Success", 
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation=Utilization.class))
	)
	@GetMapping("/utilization")
	public Utilization getUtilization() {
		return UtilizationService.getMostRecent();
	}
	/**
	 * Report the health of the application, returning 200 OK if the application is health,
	 * or 503 Service unavailable if it is not.
	 * @param resp	The response.
	 * @return The Health of the application.
	 */
	@Operation(summary = "Get the health status of the application",
			description = "Returns the health status of the application")
	@ApiResponse(responseCode = "200", description = "Application is Healthy", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = Health.class))
	)
	@ApiResponse(responseCode = "503", description = "Application is Not Healthy", 
	    content = @Content(mediaType = "application/json", 
	     schema = @Schema(implementation = Health.class))
	)
	@GetMapping("/healthy")
	public Health isHealthy(HttpServletResponse resp) {
		Health h = HealthService.getHealth();
		if (!h.isHealthy()) {
			resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		}
		return h;
	}
}
