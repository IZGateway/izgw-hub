package gov.cdc.izgateway.ads;


import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.FaultSupport;
import gov.cdc.izgateway.soap.fault.MessageSupport;

import org.apache.commons.lang3.StringUtils;

public class MetadataFault extends Fault implements FaultSupport {
    private static final long serialVersionUID = 1L;

    private static final MessageSupport[] MESSAGE_TEMPLATE = {
        new MessageSupport("MetadataFault", "70", "Error in Metadata", null,
            "An illegal value was provided for metadata", RetryStrategy.CORRECT_MESSAGE),
        new MessageSupport("MetadataFault", "71", "Multiple Errors in Metadata", null,
            "Illegal values were provided for metadata", RetryStrategy.CORRECT_MESSAGE),
    };
    static {
    	for (MessageSupport m: MESSAGE_TEMPLATE) {
    		MessageSupport.registerMessageSupport(m);
    	}
    }
    private final Metadata meta;

    public MetadataFault(Metadata meta, String ... errors) {
        this(meta, null, errors);
    }
    public MetadataFault(Metadata meta, Throwable cause, String ... errors) {
        super(MESSAGE_TEMPLATE[errors.length > 1 ? 1 : 0].copy().setDetail(StringUtils.join(errors, "\n")), cause);
        this.meta = meta;
        if (cause != null) {
            initCause(cause);
        }
    }

    public Metadata getMeta() {
        return meta;
    }

    @Override
    public String getSummary() {
        return messageSupport.getSummary();
    }

    @Override
    public String getDetail() {
        return messageSupport.getDetail();
    }

    @Override
    public String getDiagnostics() {
        return messageSupport.getDiagnostics();
    }

    @Override
    public String getCode() {
        return messageSupport.getCode();
    }

    @Override
    public RetryStrategy getRetry() {
        return messageSupport.getRetry();
    }

    @Override
    public String getFaultName() {
        return messageSupport.getFaultName();
    }
}
