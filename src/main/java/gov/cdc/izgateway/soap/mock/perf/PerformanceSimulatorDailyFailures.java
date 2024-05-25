package gov.cdc.izgateway.soap.mock.perf;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.soap.fault.Fault;

/**
 * Enables simulation of IIS performance on inbound connections to the IIS Mock Endpoint
 * This class provides two capabilities:
 * 1. Insertion of random errors in responses according to the distribution with which they are
 *    seen during a "typical" week, i.e., a week in which no IIS is suffering from a catastrophic failure
 * 2. Insertion of delays in producing a response to a request based on normal response times from the
 *    present IZGW IIS endpoints.
 */
public class PerformanceSimulatorDailyFailures extends AbstractPerformanceSimulator {
    @SuppressWarnings("unused")
	private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceSimulatorDailyFailures.class);

    /*
     *  2       Connection reset        DestinationConnectionFault  Reset connection: Get to the socket and close it if possible, else return HTTP Error code
     *  4       HTTP 404                HubClientFault
     *  4       HTTP 408                HubClientFault
     *  10      HTTP 403                HubClientFault
     *  57      HTTP 503                HubClientFault
     *  78      HTTP 502                HubClientFault
     *  113     Connect Timeout         DestinationConnectionFault  Sleep for 15,000 ms, then reset connection (close but not quite, we cannot simulate this exactly)
     *  185     Control Characters      HubClientFault  Return a General SOAP Fault (simulate a parsing error)
     *  253     TLS Connection Error    DestinationConnectionFault  Return 403 (Cannot simulate this easily)
     *  322     Destination IIS         HubClientFault  Return a Security Fault after 250ms delay
     *          Authentication Error
     *  439     No running              HubClientFault  Return a UnknownFault_Message with no delay
     *          communication point
     *  632     Other Errors            HubClientFault  Return a UnknownFault_Message after 250 ms delay
     *  2690    Read Timeout            DestinationConnectionFault  Sleep for 63,000 ms, then reset connection (should simulate this)
     *  2891    Connection Refused      DestinationConnectionFault  Reset connection (we cannot simulate this)
     *  5987    End of File/EOF         HubClientFault  Sleep then reset the connection.
     */
    public enum ErrorType implements Simulator {
        /* Keep these in sorted order by frequency */
        CONNECTION_RESET(2, AbstractPerformanceSimulator::simulateConnectionReset),
        HTTP_404(4, AbstractPerformanceSimulator::simulateHttp404),
        HTTP_408(4, AbstractPerformanceSimulator::simulateHttp408),
        HTTP_403(10, AbstractPerformanceSimulator::simulateHttp403),
        HTTP_503(57, AbstractPerformanceSimulator::simulateHttp503),
        HTTP_502(78, AbstractPerformanceSimulator::simulateHttp502),
        CONNECTION_TIMEOUT(113, AbstractPerformanceSimulator::simulateConnectTimeout),
        CONTROL_CHARS(185, AbstractPerformanceSimulator::simulateControlChars),
        TLS_CONNECTION_ERROR(253, AbstractPerformanceSimulator::simulateTLSConnectionError),
        AUTHENTICATION_ERROR(322, AbstractPerformanceSimulator::simulateAuthenticationError),
        NO_RUNNING_COMMUNICATION_POINT(439, AbstractPerformanceSimulator::simulateNoRunningCommunicationPoint),
        OTHER_FAULT(632, AbstractPerformanceSimulator::simulateOtherFault),
        READ_TIMEOUT(2690, AbstractPerformanceSimulator::simulateReadTimeout),
        CONNECTION_REFUSED(2891, AbstractPerformanceSimulator::simulateConnectionRefused),
        END_OF_FILE(5987, AbstractPerformanceSimulator::simulateEndOfFile);
        private int freq;
		private Callable1<HttpServletResponse> callable1;
        ErrorType(int freq, Callable1<HttpServletResponse> callable1) {
            this.callable1 = callable1;
            this.freq = freq;
        }
        public ResponseEntity<String> simulate(HttpServletResponse resp) throws Fault {
            return this.callable1.call(resp);
        }
        public int getFrequency() {
            return freq;
        }
    }

    @Override
    public void initErrors() {
        errors = Collections.unmodifiableList(Arrays.asList(ErrorType.values()));
        maxErrorValue = errors.stream().collect(Collectors.summingInt(Simulator::getFrequency));
    }
}
