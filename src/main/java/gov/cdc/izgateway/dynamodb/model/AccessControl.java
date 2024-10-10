package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.MappableEntity;
import gov.cdc.izgateway.utils.SystemUtils;


/**
 * Supports Access Controls with a set of Access Control Entries
 *
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
public class AccessControl extends DynamoDbEntity implements Serializable, IAccessControl {
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
    
    @Schema(description = "The environment")
    private int destType;
    
    @Override
    public String toString() {
    	return String.format("%s: %s allows access to %s", category, name, member);
    }

	@Override
	public String primaryId() {
		return destType + "#" + category + "#" + name + "#" + member;
	}

	@DynamoDbIgnore
	@Override
	public boolean isAllowed() {
		return true;
	}

	@Override
	public void setAllowed(boolean allowed) {
		// Do Nothing
	}

	/**
	 * Copy constructor
	 * @param control The AccessControl to copy
	 */
	public AccessControl(IAccessControl control) {
		this.name = control.getName();
		this.member = control.getMember();
		this.category = control.getCategory();
		this.destType = SystemUtils.getDestType();
		if (!control.isAllowed()) {
			throw new IllegalStateException("Cannot copy an Access Control exclusion");
		}
	}
}
