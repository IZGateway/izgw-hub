package gov.cdc.izgateway.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.MappableEntity;


@SuppressWarnings("serial")
@Entity
@IdClass(AccessControlId.class)
@Table(name = "accesscontrol")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccessControl  implements Serializable, IAccessControl {
	public static class Map extends MappableEntity<AccessControl>{}

    @Id
    @Schema(description = "The access control category")
	private String category;

    @Id
    @Schema(description = "The access control name")
    private String name;

    @Id
    @Schema(description = "The access control member")
    private String member;
    
    @Column(name = "allow")
    @Schema(description = "True if member can be in name for category")
    private boolean allowed;
    
    public AccessControl(String category, String name, String member) {
    	this(category, name, member, true);
    }
    
    public String toString() {
    	return String.format("%s: %s %s %s", category, name, 
    			allowed ? " allows access to " : " does not allow access to ", member);
    }
}
