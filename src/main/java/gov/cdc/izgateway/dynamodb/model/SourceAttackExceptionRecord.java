package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;

import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for SourceAttackExceptionRecord (IGDD-2805), representing a sender exempted from
 * source-attack auto-lockout.
 * <p>
 * Unlike sibling entities such as {@code DenyListRecord}/{@code FileType}, this class does not
 * implement an izgw-core marker interface (e.g. an {@code ISourceAttackException}). There is no
 * old-model/migration counterpart for this capability and nothing outside izgw-hub needs to
 * reference it abstractly, so adding one would require an izgw-core version bump for no
 * functional benefit.
 * </p>
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class SourceAttackExceptionRecord extends DynamoDbAudit implements DynamoDbEntity, Serializable {
	private String sender;
	private String reason;
	private int environment;

	@Override
	public String getPrimaryId() {
		// The sort key is {environment}#{sender}
		return String.format("%d#%s", this.environment, this.sender);
	}
}
