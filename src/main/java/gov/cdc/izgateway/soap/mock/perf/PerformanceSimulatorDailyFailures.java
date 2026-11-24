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
    /**
     * Make a simulator with the error frequencies of a typical week.
     */
    public PerformanceSimulatorDailyFailures() {
        // Nothing to initialize. The superclass constructor calls initErrors().
    }

    /**
     * The simulated error types. The number is the frequency of the error in one million requests.
     */
    public enum ErrorType implements Simulator {
        /* Keep these in sorted order by frequency */
        /** Reset the connection. */
        CONNECTION_RESET(2, AbstractPerformanceSimulator::simulateConnectionReset),
        /** Return HTTP 404 (Not Found). */
        HTTP_404(4, AbstractPerformanceSimulator::simulateHttp404),
        /** Return HTTP 408 (Request Timeout). */
        HTTP_408(4, AbstractPerformanceSimulator::simulateHttp408),
        /** Return HTTP 403 (Forbidden). */
        HTTP_403(10, AbstractPerformanceSimulator::simulateHttp403),
        /** Return HTTP 503 (Service Unavailable). */
        HTTP_503(57, AbstractPerformanceSimulator::simulateHttp503),
        /** Return HTTP 502 (Bad Gateway). */
        HTTP_502(78, AbstractPerformanceSimulator::simulateHttp502),
        /** Write a byte after 1, 2 and 4 seconds, wait 8 seconds, then reset the connection. */
        CONNECTION_TIMEOUT(113, AbstractPerformanceSimulator::simulateConnectTimeout),
        /** Return a fault for invalid characters in XML. */
        CONTROL_CHARS(185, AbstractPerformanceSimulator::simulateControlChars),
        /** Reset the connection to simulate a TLS connection error. */
        TLS_CONNECTION_ERROR(253, AbstractPerformanceSimulator::simulateTLSConnectionError),
        /** Return a security fault. */
        AUTHENTICATION_ERROR(322, AbstractPerformanceSimulator::simulateAuthenticationError),
        /** Wait 5 seconds, then return a fault for no running communication point. */
        NO_RUNNING_COMMUNICATION_POINT(439, AbstractPerformanceSimulator::simulateNoRunningCommunicationPoint),
        /** Return an unknown fault. */
        OTHER_FAULT(632, AbstractPerformanceSimulator::simulateOtherFault),
        /** Write part of a response, then wait 63 seconds. */
        READ_TIMEOUT(2690, AbstractPerformanceSimulator::simulateReadTimeout),
        /** Reset the connection to simulate a refused connection. */
        CONNECTION_REFUSED(2891, AbstractPerformanceSimulator::simulateConnectionRefused),
        /** Write part of a response, wait, then reset the connection. */
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
