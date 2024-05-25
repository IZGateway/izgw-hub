package gov.cdc.izgateway.ads;

import gov.cdc.izgateway.common.ErrorResponse;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.soap.fault.FaultSupport;


public class ADSErrorResponse extends ErrorResponse {
    private final FaultSupport fs;
    private RetryStrategy rs;
    public ADSErrorResponse(FaultSupport fs, String eventId) {
    	super(eventId, fs.getMessage(), fs.getSummary(), fs.getDetail());
        this.fs = fs;
        rs = fs.getRetry();
        super.setDiagnostics(fs.getDiagnostics());
    }
    
    @Override
    public String getDiagnostics() {
        return fs.getDiagnostics();
    }

    public String getCode() {
        return fs.getCode();
    }
    public RetryStrategy getRetryStrategy() {
        return rs;
    }
    public String getFaultName() {
        return fs.getFaultName();
    }

    public void setRetryStrategy(RetryStrategy rs) {
        this.rs = rs;
    }

	@Override
	public void setDetail(String detail) {
		this.detail = detail;
	}
}