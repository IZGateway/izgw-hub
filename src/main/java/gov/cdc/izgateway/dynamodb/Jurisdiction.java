package gov.cdc.izgateway.dynamodb;

import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.MappableEntity;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@SuppressWarnings("serial")
@Entity
@Table(name="jurisdiction")
@Data
public class Jurisdiction implements IJurisdiction {
	@Schema(properties= {
			@StringToClassMapItem(key="Alaska", value=Destination.class),
			@StringToClassMapItem(key="CDC", value=Destination.class),
			@StringToClassMapItem(key="Development", value=Destination.class),
			@StringToClassMapItem(key="Maryland", value=Destination.class),
			@StringToClassMapItem(key="Wyoming", value=Destination.class)
		})
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
}
