package gov.cdc.izgateway.dynamodb.model;

import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * An entity to get jurisdiction information
 * 
 * @author Audacious Inquiry
 *
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=true)
@DynamoDbBean
public class Jurisdiction extends DynamoDbAudit implements DynamoDbEntity, IJurisdiction {
	/**
	 * A map of jurisdictions.
	 * 
	 * @author Audacious Inquiry
	 */
	@Schema(properties= {
			@StringToClassMapItem(key="Alaska", value=Destination.class),
			@StringToClassMapItem(key="CDC", value=Destination.class),
			@StringToClassMapItem(key="Development", value=Destination.class),
			@StringToClassMapItem(key="Maryland", value=Destination.class),
			@StringToClassMapItem(key="Wyoming", value=Destination.class)
		})
	public static class Map extends MappableEntity<Jurisdiction>{}

	/**
	 * Create a new Jurisdiction
	 */
	public Jurisdiction() {
	}
	/**
	 * Create a copy from an existing Jurisdiction
	 *  
	 * @param that	The jurisdiction to copy.
	 */
	public Jurisdiction(IJurisdiction that) {
		this.description = that.getDescription();
		this.jurisdictionId = that.getJurisdictionId();
		this.name = that.getName();
		this.prefix = that.getPrefix();
	}

    @Schema(description="The identifier of the jurisdiction.")
	private int jurisdictionId;
    public int getJurisdictionId() {
    	return jurisdictionId;
    }
    
    @Schema(description="The name of the jurisdiction.")
	private String name;
    
    @Schema(description="The description of the jurisdiction.")
	private String description;
    
    @Schema(description="The prefix to use for destinations managed by this jurisdiction.")
	private String prefix;
    
    @Schema(description="The vendor for the jurisdiction.")
    private String vendor;
    
	@Override
	public String getPrimaryId() {
		return  Integer.toString(jurisdictionId);
	}
}
