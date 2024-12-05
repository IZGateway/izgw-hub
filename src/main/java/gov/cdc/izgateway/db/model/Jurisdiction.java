package gov.cdc.izgateway.db.model;

import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A record for a jurisdiction.
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Entity
@Table(name="jurisdiction")
@Data
public class Jurisdiction implements IJurisdiction {
	/**
	 * @author Audacious Inquiry
	 *
	 */
	@Schema(properties= {
			@StringToClassMapItem(key="Alaska", value=Destination.class),
			@StringToClassMapItem(key="CDC", value=Destination.class),
			@StringToClassMapItem(key="Development", value=Destination.class),
			@StringToClassMapItem(key="Maryland", value=Destination.class),
			@StringToClassMapItem(key="Wyoming", value=Destination.class)
		})
	/** The map for Swagger documentation */
	public static class Map extends MappableEntity<Jurisdiction>{}

    @Id
    @Column(name="jurisdiction_id")
    @Schema(description="The identifier of the jurisdiction.")
	private int jurisdictionId;
    @Schema(description="The name of the jurisdiction.")
    @Column(name="name")
	private String name;
    @Schema(description="The description of the jurisdiction.")
    @Column(name="description")
	private String description;
    @Schema(description="The prefix to use for destinations managed by this jurisdiction.")
    @Column(name="dest_prefix")
	private String prefix;
    
	/**
	 * Construct a new jurisidiction record.
	 */
	public Jurisdiction() {
	}

	/**
	 * Construct a new jurisidiction record from an existing one
	 * @param j The existing jurisdiction to copy from
	 */
	public Jurisdiction(IJurisdiction j) {
		this.jurisdictionId = j.getJurisdictionId();
		this.name = j.getName();
		this.description = j.getDescription();
		this.prefix = j.getPrefix();
	}
}
