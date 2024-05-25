package gov.cdc.izgateway.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;

import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;


@SuppressWarnings("serial")
@Entity
@Table(name = "messageheaderinfo")
@Data
@Schema(description="Mappings from Message Header values to sources")
public class MessageHeader implements Serializable, IMessageHeader {
	@Schema(properties= {
			@StringToClassMapItem(key="AKIIS", value=Destination.class),
			@StringToClassMapItem(key="IZGW", value=Destination.class),
			@StringToClassMapItem(key="MDIIS", value=Destination.class),
			@StringToClassMapItem(key="WIR", value=Destination.class)
		})
	public static class Map extends MappableEntity<MessageHeader>{}
    @Id
    @Column(name = "msh",nullable = false)
    @Schema(description="The messsage header to use for this source")
    private String msh;

    @Column(name = "dest_id")
    @Schema(description="The destination identifier associated with this MSH-3/4 value")
    private String destId;

    @Column(name = "iis")
    @Schema(description="The jurisdiction associated with this MSH-3/4 value")
    private String iis;
    
    @Column(name = "sourceType")
    @Schema(description="The type of sender using this MSH-3/4 value")
    private String sourceType;

    @Column(name = "username")
    @Schema(description="The username required by the dev endpoint when mocking this endpoint", hidden=true)
    private String username;
    
    @Column(name = "password")
    @Schema(description="The password required by the dev endpoint when mocking this endpoint", hidden=true)
    private String password;
    
    @Column(name = "facility_id")
    @Schema(description="The facility identifier for this source", hidden=true)
    private String facilityId;

    public MessageHeader() {
    }
	public MessageHeader(IMessageHeader that) {
		this.setDestId(that.getDestId());
		this.setFacilityId(that.getFacilityId());
		this.setIis(that.getIis());
		this.setMsh(that.getMsh());
		this.setPassword(that.getPassword());
		this.setSourceType(that.getSourceType());
		this.setUsername(that.getUsername());
	}

}
