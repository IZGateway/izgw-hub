package gov.cdc.izgateway.dynamodb.model;

import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An entity to get jurisdiction information
 * 
 * @author Audacious Inquiry
 *
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
public class Jurisdiction extends DynamoDbEntity implements IJurisdiction {
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
    
	@Override
	public String primaryId() {
		return Integer.toString(jurisdictionId);
	}
}
