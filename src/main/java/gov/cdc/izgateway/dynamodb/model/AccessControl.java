package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.MappableEntity;


/**
 * Supports Access Controls with a set of Access Control Entries
 *
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccessControl  implements Serializable, IAccessControl {
	/**
	 * Provides easy access to the Map class for Swagger documentation
	 */
	public static class Map extends MappableEntity<AccessControl>{}

    @Schema(description = "The access control category")
	private String category;

    @Schema(description = "The access control name")
    private String name;

    @Schema(description = "The access control member")
    private String member;
    
    @Schema(description = "True if member can be in name for category")
    private boolean allowed;
    
    /**
     * Create a new access control entry.
     * @param category The category of the access control entry
     * @param name	The name of the entry
     * @param member Members of the entry
     */
    public AccessControl(String category, String name, String member) {
    	this(category, name, member, true);
    }
    
    @Override
    public String toString() {
    	return String.format("%s: %s %s %s", category, name, 
    			allowed ? "allows access to" : "does not allow access to", member);
    }
}