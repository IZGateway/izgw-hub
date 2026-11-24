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
// javadoc reads the source without Lombok, so it does not see the constructor that
// Lombok generates (@NoArgsConstructor and @AllArgsConstructor). It then reports "use of default constructor, which does
// not provide a comment". The real constructor exists. This suppression hides only
// missing-comment warnings in this class (IGDD-2974). Other javadoc checks still apply.
@SuppressWarnings({"serial", "doclint:missing"})
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class SourceAttackExceptionRecord extends DynamoDbAudit implements DynamoDbEntity, Serializable {
	/** The common name of the sender that is exempt from source-attack auto-lockout. */
	private String sender;
	/** The operator justification for the exception. */
	private String reason;
	/** The environment (destination type) of the record. */
	private int environment;

	@Override
	public String getPrimaryId() {
		// The sort key is {environment}#{sender}
		return String.format("%d#%s", this.environment, this.sender);
	}
}
