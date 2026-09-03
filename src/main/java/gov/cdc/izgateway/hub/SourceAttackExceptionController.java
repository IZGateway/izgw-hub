package gov.cdc.izgateway.hub;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.security.RolesAllowed;
import lombok.Data;

/**
 * Admin API for managing source-attack lockout exceptions (IGDD-2805).
 * <p>
 * A sender with a configured exception is not added to the deny list when a source attack is
 * detected for it; the triggering message is still rejected. See
 * {@code openspec/changes/igdd-2805-source-attack-lockout/design.md}.
 * </p>
 */
@RestController
@CrossOrigin
@RolesAllowed({ Roles.ADMIN })
@RequestMapping({ "/rest/sourceAttackExceptions" })
@Lazy(false)
public class SourceAttackExceptionController {
    private final AccessControlService accessControlService;

    /**
     * Request body for creating a source-attack exception.
     */
    @Data
    public static class CreateExceptionRequest {
        @Schema(description = "The sender's common name")
        private String sender;
        @Schema(description = "Operator justification for the exception")
        private String reason;
    }

    /**
     * Create the controller.
     * @param registry	The registry for managing access control to methods
     * @param accessControlService	The access control service
     */
    @Autowired
    public SourceAttackExceptionController(AccessControlRegistry registry, AccessControlService accessControlService) {
        registry.register(this);
        this.accessControlService = accessControlService;
    }

    @Operation(summary = "List source-attack lockout exceptions",
            description = "Returns all senders currently exempted from source-attack auto-lockout")
    @ApiResponse(responseCode = "200", description = "The configured exceptions",
        content = @Content(mediaType = "application/json",
         schema = @Schema(implementation = SourceAttackExceptionRecord.class)))
    @GetMapping
    public List<SourceAttackExceptionRecord> list() {
        return accessControlService.listSourceAttackExceptions();
    }

    @Operation(summary = "Create a source-attack lockout exception",
            description = "Exempts a sender from source-attack auto-lockout. Does not affect whether a "
                    + "flagged message is accepted or rejected.")
    @ApiResponse(responseCode = "200", description = "The created exception",
        content = @Content(mediaType = "application/json",
         schema = @Schema(implementation = SourceAttackExceptionRecord.class)))
    @ApiResponse(responseCode = "400", description = "sender or reason was blank", content = @Content)
    @PostMapping
    public SourceAttackExceptionRecord create(@RequestBody CreateExceptionRequest request) {
        return accessControlService.createSourceAttackException(request.getSender(), request.getReason());
    }

    @Operation(summary = "Remove a source-attack lockout exception",
            description = "The sender will again be added to the deny list on the next detected source attack")
    @ApiResponse(responseCode = "204", description = "The exception was removed, or did not exist", content = @Content)
    @DeleteMapping("/{sender}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sender) {
        accessControlService.deleteSourceAttackException(sender);
    }
}
