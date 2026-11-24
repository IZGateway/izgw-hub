package gov.cdc.izgateway.ads;

import gov.cdc.izgateway.common.ErrorResponse;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.soap.fault.FaultSupport;


/** An error response for the ADS service. The values come from a fault. */
public class ADSErrorResponse extends ErrorResponse {
    private final FaultSupport fs;
    private RetryStrategy rs;
    /**
     * Creates an error response from a fault.
     * @param fs the fault
     * @param eventId the event ID of the request
     */
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

    /**
     * Gets the fault code.
     * @return the fault code
     */
    public String getCode() {
        return fs.getCode();
    }
    /**
     * Gets the retry strategy.
     * @return the retry strategy
     */
    public RetryStrategy getRetryStrategy() {
        return rs;
    }
    /**
     * Gets the fault name.
     * @return the fault name
     */
    public String getFaultName() {
        return fs.getFaultName();
    }

    /**
     * Sets the retry strategy.
     * @param rs the retry strategy
     */
    public void setRetryStrategy(RetryStrategy rs) {
        this.rs = rs;
    }

	@Override
	public void setDetail(String detail) {
		this.detail = detail;
	}
}