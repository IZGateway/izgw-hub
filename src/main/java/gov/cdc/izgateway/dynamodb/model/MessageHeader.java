package gov.cdc.izgateway.dynamodb.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 
 * Message header captures information associated with message header values
 * including the destination identifier, the jurisdiction, and the type of 
 * sender.  It also allows user names, passwords, and facility identifiers
 * to be associated with jurisdictions pointing to the mock endpoint.
 *  
 * @author Audacious Inquiry
 *
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@Schema(description="Mappings from Message Header values to sources")
@DynamoDbBean
public class MessageHeader extends DynamoDbEntity implements Serializable, IMessageHeader {
	/**
	 * An alias for the message header map supporting Swagger 
	 * documentation of this resource.
	 */
	@Schema(properties= {
			@StringToClassMapItem(key="AKIIS", value=Destination.class),
			@StringToClassMapItem(key="IZGW", value=Destination.class),
			@StringToClassMapItem(key="MDIIS", value=Destination.class),
			@StringToClassMapItem(key="WIR", value=Destination.class)
		})
	public static class Map extends MappableEntity<MessageHeader>{}
	
    @Schema(description="The messsage header to use for this source")
    private String msh;
	
	/**
	 * Get the MSH value associated with this header entry.
	 * Sadly, @@DynamoDBPartionKey only works on method calls, not
	 * data elements.  
	 */
	public String getMsh() {
		return msh;
	}
    
    @Schema(description="The destination identifier associated with this MSH-3/4 value")
    private String destId;

    @Schema(description="The jurisdiction associated with this MSH-3/4 value")
    private String iis;
    
    @Schema(description="The type of sender using this MSH-3/4 value")
    private String sourceType;

    @Schema(description="The username required by the dev endpoint when mocking this endpoint", hidden=true)
    private String username;  // Not a real user name, used in testing
    
    @Schema(description="The password required by the dev endpoint when mocking this endpoint", hidden=true)
    private String password;  // Not a secret password, used in testing
    
    @Schema(description="The facility identifier for this source", hidden=true)
    private String facilityId;

    /**
     * Create a new MessageHeader
     */
    public MessageHeader() {
    }
    /**
     * Create a new message header that copies what is in another MessageHeader
     * @param that	The other message header
     */
	public MessageHeader(IMessageHeader that) {
		this.setDestId(that.getDestId());
		this.setFacilityId(that.getFacilityId());
		this.setIis(that.getIis());
		this.setMsh(that.getMsh());
		this.setPassword(that.getPassword());
		this.setSourceType(that.getSourceType());
		this.setUsername(that.getUsername());
	}

  @Override
	public String getPrimaryId() {
		return getMsh();
	}
}
