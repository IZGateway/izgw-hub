package gov.cdc.izgateway.soap.mock.perf;

import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import jakarta.servlet.http.HttpServletResponse;

public interface PerformanceSimulatorInterface {
    ResponseEntity<?> simulateError(HttpServletResponse resp) throws Fault;
    SubmitSingleMessageResponse getResponse(SubmitSingleMessageRequest requestMessage) throws Fault;
}
