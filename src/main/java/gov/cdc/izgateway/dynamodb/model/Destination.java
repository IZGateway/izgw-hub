package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;

import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.common.HasDestinationUri;
import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IDestinationId;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.service.JurisdictionService;
import gov.cdc.izgateway.utils.SystemUtils;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Date;

/**
 * A Destination stored in a Dynamo Database
 * @author Audacious Inquiry
 *
 */
@DynamoDbBean
@SuppressWarnings("serial")
@JsonPropertyOrder({ "destId", "destType", "destUri", "destVersion", "facilityId", "msh3", "msh4", "msh5", "msh6",
		"msh22", "rxa11" })
@Data
@EqualsAndHashCode(callSuper=false)
public class Destination extends DynamoDbEntity implements IEndpoint, Serializable, HasDestinationUri, IDestination {
	/**
	 * A destination id.
	 * A composite of the destination endpoint identifier, and the environment id (a.k.a., destination type). 
	 * @author Audacious Inquiry
	 */
	@Schema(properties= {
		@StringToClassMapItem(key="ak", value=Destination.class),
		@StringToClassMapItem(key="dev", value=Destination.class),
		@StringToClassMapItem(key="dex", value=Destination.class),
		@StringToClassMapItem(key="dex-dev", value=Destination.class),
		@StringToClassMapItem(key="md", value=Destination.class),
		@StringToClassMapItem(key="md_c", value=Destination.class),
		@StringToClassMapItem(key="wy", value=Destination.class)
	})
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class DestinationId implements Serializable, IDestinationId {
		private String destId;
		private int destType;

		/**
		 * Create a new destination identifier from an existing one. 
		 * @param id
		 */
		public DestinationId(IDestinationId id) {
			this.destId = id.getDestId();
			this.destType = id.getDestType();
		}
		
		@Override
		public DestinationId copy() {
			return new DestinationId(destId, destType);
		}

		@Override
		public void setDestType(String destType) {
			int result = SystemUtils.getDestTypeId(destType);
			if (result < 1) {
				throw new IllegalArgumentException("Destination Type must come from " + SystemUtils.getDestTypes());
			}
			this.destType = result;
		}

		@Override
		public void setDestType(int destType) {
			this.destType = destType;
		}
		
		@Override
		public String toString() {
			return String.format("%d#%s", destType, destId);
		}
	}

	@Schema(hidden = true)
	private DestinationId id = new DestinationId();
	public void setId(IDestinationId id) {
		if (id instanceof DestinationId did) {
			this.id = did;
		} else {
			this.id = new DestinationId(id);
		}
	}
	@DynamoDbIgnore
	public DestinationId getId() {
		return this.id;
	}
	
	@Schema(description = "The destination endpoint URL", pattern=ID_PATTERN)
	private String destUri;// NOT NULL

	@JsonIgnore
	@Schema(description = "The destination endpoint username", hidden=true)
	private String username;

	@JsonIgnore
	@Schema(description = "The destination endpoint password", hidden=true)
	private String password;

	@Schema(description = "The schema or protocol version for use with the endpoint", 
		hidden=true, pattern="2011|2014|V2022-12-31|DEX1.0")
	private String destVersion;

	@JsonIgnore
	@Schema(description = "The jurisdiction responsible for the endpoint", hidden=true)
	private int jurisdictionId;

	@Schema(description = "The reason for destination maintenance")
	private String maintReason;

	@Schema(description = "The start of the maintenance period")
	@JsonFormat(shape = Shape.STRING, pattern = Constants.TIMESTAMP_FORMAT)
	private Date maintStart;

	@Schema(description = "The end of the maintenance period, or null if unspecified")
	private Date maintEnd;

	@Schema(description = "The identifier of the facility to use with test messages for this endpoint")
	private String facilityId;

	@Schema(description = "The MSH3 value to use with test messages for this endpoint")
	private String msh3;
	
	@Schema(description = "The MSH4 value to use with test messages for this endpoint")
	private String msh4;
	
	@Schema(description = "The MSH5 value to use with test messages for this endpoint")
	private String msh5;
	
	@Schema(description = "The MSH6 value to use with test messages for this endpoint")
	private String msh6;
	
	@Schema(description = "The MSH22 value to use with test messages for this endpoint")
	private String msh22;
	
	@Schema(description = "The RXA11 value to use with test messages for this endpoint")
	private String rxa11;

	/**
	 * Create a new Destination
	 */
	public Destination() {
		id.setDestType(SystemUtils.getDestType());
	}

	/**
	 * Create a new Destination as a copy of an existing one.
	 * @param that The destination to make a copy of
	 */
	public Destination(IDestination that) {
		if (that.getId() instanceof DestinationId did) {
			this.id = did;
		} else {
			this.id = new DestinationId(that.getId());
		}
		this.destUri = that.getDestUri();
		this.destVersion = that.getDestVersion();
		this.username = that.getUsername();
		this.password = that.getPassword();
		this.jurisdictionId = that.getJurisdictionId();
		this.maintReason = that.getMaintReason();
		this.maintStart = that.getMaintStart();
		this.maintEnd = that.getMaintEnd();
		this.facilityId = that.getFacilityId();
		this.msh3 = that.getMsh3();
		this.msh4 = that.getMsh4();
		this.msh5 = that.getMsh5();
		this.msh6 = that.getMsh6();
		this.msh22 = that.getMsh22();
		this.rxa11 = that.getRxa11();
	}
	
	/**
	 * Create an example for Swagger documentation
	 * @param destId	The example name
	 * @return	An example destition
	 */
	public static Destination getExample(String destId) {
		Destination dest = new Destination();
		dest.id.setDestId(destId);
		dest.destUri = "https://example.com/dev/IISService";
		dest.destVersion = "2011";
		// This is for documentation, not an exposure of private credentials
		dest.username = "username";
		dest.password = "password";
		dest.jurisdictionId = 1;
		dest.maintReason = null;
		dest.maintStart = null;
		dest.maintEnd = null;
		dest.facilityId = null;
		dest.msh3 = "IZGW";
		dest.msh4 = "IZGW";
		dest.msh5 = "IZGW";
		dest.msh6 = "IZGW";
		dest.msh22 = "IZGW";
		dest.rxa11 = "IZGW";
		return dest;
	}

	/**
	 * The destination type name.
	 */
	@DynamoDbIgnore
	@Schema(description = "The type of destination")
	public String getDestType() {
		return SystemUtils.getDestTypes().get(id.getDestType()-1);
	}

	@JsonIgnore
	public int getDestTypeId() {
		return id.getDestType();
	}

	@Override
	public void setDestTypeId(int destType) {
		this.id.setDestType(destType);
	}

	@DynamoDbIgnore
	@Schema(description = "The IIS or other name for the jurisdiction")
	public String getJurisdictionName() {
		IJurisdiction j = JurisdictionService.getInstance().getJurisdiction(jurisdictionId);
		return j == null ? null : j.getName();
	}

	@DynamoDbIgnore
	@Schema(description = "A description of the jurisdiction (typically the state or other name)")
	public String getJurisdictionDesc() {
		IJurisdiction j = JurisdictionService.getInstance().getJurisdiction(jurisdictionId);
		return j == null ? null : j.getDescription();
	}

	@Override
	@DynamoDbIgnore
	@JsonIgnore
	public boolean isUnderMaintenance() {
		Date now = new Date();
		if (!StringUtils.isEmpty(maintReason)) {
			return (maintStart == null || now.after(maintStart)) && (maintEnd == null || now.before(maintEnd));
		}
		return false;
	}
	
	@Override
	@DynamoDbIgnore
	@JsonIgnore
	public String getMaintenanceDetail() {
		String detail = String.format("Destination %s in %s under maintenance from %tc until ", getDestId(),
				SystemUtils.getDestTypeAsString(), getMaintStart());
		if (getMaintEnd() != null) {
			detail += String.format("%tc", getMaintEnd());
		} else {
			detail += " further notice.";
		}
		return detail;
	}

	@Override
	public Destination safeCopy() {
		Destination p = new Destination(this);
		p.username = null;
		p.password = null;
		return p;
	}

	@Override
	@Schema(description = "The destination identifier")
	public String getDestId() {
		return id.getDestId();
	}

	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(description = "True if this destination supports the original CDC 2011 Protocol", hidden=true)
	public boolean is2011() {
		return "2011".equals(destVersion);
	}

	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(description = "True if this destination supports the IZ Gateway 2014 Protocol", hidden=true)
	public boolean is2014() {
		return StringUtils.isEmpty(destVersion) || "2014".equals(destVersion);
	}
	
	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(description = "True if this destination supports the IZ Gateway Hub Protocol", hidden=true)
	public boolean isHub() {
		return "HUB".equalsIgnoreCase(destVersion);
	}
	
	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(description = "True if this destination supports the CDC DEX Protocol", hidden=true)
	public boolean isDex() {
		return "DEX1.0".equals(destVersion);
	}

	@JsonIgnore
	@Schema(description = "The destination id", hidden=true)
	@Override
	public String getDestinationId() {
		return id.getDestId();
	}

	@JsonIgnore
	@Schema(description = "The destination uri", hidden=true)
	@Override
	public String getDestinationUri() {
		return getDestUri();
	}

	@Override
	public String primaryId() {
		return id.toString();
	}
}
