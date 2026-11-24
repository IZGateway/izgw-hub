package gov.cdc.izgateway.soap.mock.perf;

import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A simulator that the mock IIS endpoint uses to make errors, delays and responses.
 */
public interface PerformanceSimulatorInterface {
    /**
     * Make a simulated error at random intervals.
     * @param resp The HTTP response to write the error to.
     * @return The error response, or null if the simulator makes no error.
     * @throws Fault When the simulated error is a SOAP fault.
     */
    ResponseEntity<?> simulateError(HttpServletResponse resp) throws Fault;
    /**
     * Make the response to a request message.
     * @param requestMessage The request message.
     * @return The response message.
     * @throws Fault When the simulator cannot make the response.
     */
    SubmitSingleMessageResponse getResponse(SubmitSingleMessageRequest requestMessage) throws Fault;
}
