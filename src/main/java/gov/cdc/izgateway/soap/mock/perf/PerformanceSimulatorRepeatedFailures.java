package gov.cdc.izgateway.soap.mock.perf;

import jakarta.servlet.http.HttpServletResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.soap.fault.Fault;

class PerformanceSimulatorRepeatedFailures extends AbstractPerformanceSimulator {
    static final int TOTAL_SAMPLE_SIZE = 100;

    public enum ErrorType implements Simulator {
        /* Keep these in sorted order by frequency */

        // IGDD-656 - rejected connection attempts
        CONNECTION_REFUSED(12, AbstractPerformanceSimulator::simulateConnectionRefused),
        CONNECTION_REJECTED(13, AbstractPerformanceSimulator::simulateConnectionReset);

        private final Callable1<HttpServletResponse> callable1;
        private final int freq;
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
    
    @Override
    protected int getSampleSize() {
    	return TOTAL_SAMPLE_SIZE;
    }
}
