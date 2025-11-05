package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import java.util.Date;

import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IAccessControl;

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

	/**
	 * Constructor for Migration
	 * @param ac	Old Access Control Record
	 * @param reportedBy	Who created it
	 * @param completed	When it was created
	 */
	public FileType(IAccessControl ac, String reportedBy, Date completed) {
		setFileTypeName(ac.getName());
		setCreatedBy(reportedBy);
		setCreatedOn(completed);
	}
}