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
import gov.cdc.izgateway.soap.fault.SecurityFault;
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
public class LogController extends LogControllerBase {

    private MemoryAppender logData = null;
    private final IDestinationService destinationService;
    private final EndpointStatusService endpointStatusService;
    private final IAccessControlService accessControlService;

    @Autowired
    public LogController(AccessControlRegistry registry, IDestinationService destinationService, EndpointStatusService endpointStatusService, IAccessControlService accessControlService) {
        super(registry);
        this.destinationService = destinationService;
        this.endpointStatusService = endpointStatusService;
        this.accessControlService = accessControlService;
    }

    @Override
    public void deleteLogs(HttpServletRequest servletReq,
                           @Parameter(required = false, description="If true, reset the specified endpoint, clearing maintenance")
                           @RequestParam(required = false) String clear) throws SecurityFault {
        super.deleteLogs(servletReq, clear);

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