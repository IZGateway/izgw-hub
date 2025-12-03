package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for FileType, representing a file type record.
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class FileType extends DynamoDbAudit implements DynamoDbEntity, Serializable, IFileType {
    private String fileTypeName;
    private String description;
	@Override
	public String getPrimaryId() {
		return fileTypeName;
	}

    /**
     * Copy constructor
     * @param other	the other FileType object to copy from
     */
    public FileType(IFileType other) {
        super(other);
        if (other != null) {
            this.fileTypeName = other.getFileTypeName();
            this.description = other.getDescription();
        }
    }
}