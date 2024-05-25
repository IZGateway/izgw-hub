package gov.cdc.izgateway.mock.perf;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.soap.message.FaultMessage;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import gov.cdc.izgateway.soap.mock.perf.AbstractPerformanceSimulator;
import gov.cdc.izgateway.soap.mock.perf.AbstractPerformanceSimulator.Simulator;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorDailyFailures;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorDailyFailures.ErrorType;
import gov.cdc.perf.histogram.ArrayHistogram;
import gov.cdc.perf.histogram.Histogram;
import lombok.extern.slf4j.Slf4j;

/**
 * Test the performance simulator.
 * Performs some number of test runs against the DevIisService
 * and see if the response patterns match the expected
 * cumulative distribution function for:
 * a) Size
 * b) Time
 * c) Exceptions thrown
 */
@Slf4j
class TestPerformanceSimulator extends TestMockBase {
    private static final String QBP = "MSH|^~\\&|IZGW|IZGW|TEST|PERF|20210402091512.000-0100||QBP^Q11^QBP_Q11|20210330093013AZQ231|P|2.5.1|||ER|AL|||||Z34^CDCPHINVS|IZGW";
    private static final String VXU = "MSH|^~\\&|IZGW|IZGW|TEST|PERF|20210402091512.000-0100||VXU^V04^VXU_V04|20210330093013AZQ231|P|2.5.1|||ER|AL|||||Z22^CDCPHINVS|IZGW";

    private static final int MAX_RUNS = 1000;  
    private static final int MAX_THREADS_PER_CORE = 20;
    private static final int MAX_WAIT = 300;
    private static final double ALPHA = 0.05;

    private static class CollectedData {
        List<Integer> timings = Collections.synchronizedList(new ArrayList<>(MAX_RUNS));
        List<Integer> sizes = Collections.synchronizedList(new ArrayList<>(MAX_RUNS));
        Map<Simulator, Integer> exceptions = Collections.synchronizedMap(new TreeMap<Simulator, Integer>());
    };

    private static class KS_Stats {
        boolean pass;
        double ksValue;
        double ksCrit;
        double scales[] = new double[2];
        String message;
    }

    private int seed;
    /**
     * Testing behavior of Mock IIS through PerformanceSimulator
     */
    @Test
    @Disabled("Disable unless making changes to the simulator b/c it takes a while to run")
    void testSimulator() throws Exception {
        // Run this test in paralell or we'll be here all day!  That's a lot of sleeping.
        ExecutorService x = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * MAX_THREADS_PER_CORE);
        CollectedData qbp_data = new CollectedData();
        CollectedData vxu_data = new CollectedData();
        AbstractPerformanceSimulator.setSeed(seed);
        
        for (int i = 0; i < MAX_RUNS; i++) {
            x.submit(() -> sendMessage(QBP, qbp_data));
            x.submit(() -> sendMessage(VXU, vxu_data));
            log.debug("Request {}", i);
        }
        boolean success = false;

        int maxWait = MAX_WAIT, interval = 3;

        // Perform normal termination of the execution queue
        x.shutdown();
        do {
            try {
                success = x.awaitTermination(interval, TimeUnit.SECONDS);
                maxWait -= interval;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (!success && maxWait > 0);
        log.info("Done with requests");
        System.err.println("\nDone");

        Assertions.assertTrue(x.isTerminated(), "All tasks not completed");
        // Now for the real work of this test
        Histogram actualValues = new ArrayHistogram(qbp_data.timings, PerformanceSimulatorDailyFailures.DELAYS);
        KS_Stats test = testResults(actualValues, PerformanceSimulatorDailyFailures.DELAYS);
        if (!test.pass) {
            System.out.printf("QBP Timing Actual:\n%s\nExpected:\n%s%n", actualValues, PerformanceSimulatorDailyFailures.DELAYS);
            log.error("QBP Timing distribution: {}", test.message);
        }
        //Assertions.assertTrue(test.pass, "Timing distribution does not match expected value for QBP");

        actualValues = new ArrayHistogram(qbp_data.sizes, PerformanceSimulatorDailyFailures.QBP_SIZES);
        test = testResults(actualValues, PerformanceSimulatorDailyFailures.QBP_SIZES);
        if (!test.pass) {
            System.out.printf("QBP Size Actual:\n%s\nExpected:\n%s\n", actualValues, PerformanceSimulatorDailyFailures.QBP_SIZES);
            log.error("QBP Size distribution: {}", test.message);
        }
        Assertions.assertTrue(test.pass, "Size distribution does not match expected value for QBP");

        actualValues = new ArrayHistogram(vxu_data.timings, PerformanceSimulatorDailyFailures.DELAYS);
        test = testResults(actualValues, PerformanceSimulatorDailyFailures.DELAYS);
        if (!test.pass) {
            System.out.printf("VXU Timing Actual:\n%s\nExpected:\n%s%n", actualValues, PerformanceSimulatorDailyFailures.DELAYS);
            log.error("VXU Timing distribution: {}", test.message);
        }
        Assertions.assertTrue(test.pass, "Timing distribution does not match expected value for VXU");

        // We don't need to worry about VXU SIZES in the simulator
        System.out.printf("QBP Exceptions: %s%n", qbp_data.exceptions);
        System.out.printf("VXU Exceptions: %s%n", vxu_data.exceptions);

        long[] expected = new long[ErrorType.values().length];
        long[] observed = new long[expected.length];
        int i = 0;
        for (ErrorType et: ErrorType.values()) {
            observed[i] = qbp_data.exceptions.getOrDefault(et, Integer.valueOf(0));
            observed[i] += vxu_data.exceptions.getOrDefault(et, Integer.valueOf(0));
            expected[i] = et.getFrequency();
            log.info("Expected: {}\tObserved: {}\t{}", expected[i], observed[i], et);
            i++;
        }
        ChiSquareTest chiSqTest = new ChiSquareTest();
        double chisq = chiSqTest.chiSquareDataSetsComparison(expected, observed);
        double alpha = chiSqTest.chiSquareTestDataSetsComparison(expected, observed);
        String message = String.format("Exception distribution does not match expected values: %f (%f)", alpha, chisq);
        log.info("Exceptions chisq = {} alpha = {}", chisq, alpha);
        Assertions.assertTrue(alpha > ALPHA, message);
    }

    private KS_Stats testResults(Histogram actualValues, Histogram expectedValues) {
        KS_Stats stats = new KS_Stats();
        stats.ksValue = actualValues.ksStatistic(expectedValues, stats.scales);
        stats.ksCrit = actualValues.ksCriticalValue(ALPHA/2, stats.scales);
        stats.message = String.format("%f <=> %f @ (%f, %f)", stats.ksValue, stats.ksCrit, stats.scales[0], stats.scales[1]);
        log.info(stats.message);
        stats.pass = stats.ksValue < stats.ksCrit;
        return stats;
    }

    /**
     * Call submitSingleMessage with a QBP or VXU formatted to trigger
     * performance testing behavior.
     * @param hl7Message The message to send
     * @return The resulting string value
     * @throws Exception Any Exception thrown by submitSingleMessage
     */
    private Object sendMessage(String hl7Message, CollectedData data) throws Exception {
        SubmitSingleMessageRequest request = new SubmitSingleMessageRequest(hl7Message);

        SubmitSingleMessageResponse response = null;
        long timer = 0;
        try {
            RequestContext.init();
            RequestContext.setResponse(new MockHttpServletResponse());
            timer = -System.currentTimeMillis();
            ResponseEntity<?> r = service.submitSoapRequest(request, null);
            assertNotNull(r, "Result from submitSoapRequest was null");
            if (r.getBody() instanceof FaultMessage fm) {
            	Throwable ex = fm.getFault();
            	if (ex != null) {
	                log.debug("Exception: {}", ex.getMessage(), ex);
                    ErrorType et = findErrorTypeInException(ex);
                    synchronized (data.exceptions) {
                        Integer value = data.exceptions.getOrDefault(et, Integer.valueOf(0));
                        data.exceptions.put(et, ++value);
                    }
            	}
            } else {
            	response = (SubmitSingleMessageResponse)r.getBody();
            }
            // Any exception thrown at this point is unexpected and should just throw
        } finally {
            timer += System.currentTimeMillis() + PerformanceSimulatorDailyFailures.OVERHEAD;
            data.timings.add((int) timer);  // difference will safely fit into an int
            if (response != null) {
                data.sizes.add(response.getHl7Message().length());
            }
            RequestContext.clear();
        }
        return null;
    }

	private ErrorType findErrorTypeInException(Throwable inEx) {
		for (Throwable ex = inEx; ex != null; ex = ex.getCause()) {
			for (StackTraceElement trace : ex.getStackTrace()) {
				String methodName = trace.getMethodName();
				
				if (trace.getClassName().equals(AbstractPerformanceSimulator.class.getName()) &&
					methodName.startsWith("simulate")
				) {
					for (ErrorType et: ErrorType.values()) {
						if (et.name().replace("_", "").equalsIgnoreCase(methodName.substring("simulate".length()))) {
							return et;
						}
					}
				}
			}
		}
		log.error(Markers2.append(inEx), "Fault generating method not recognized in exception");
		return ErrorType.OTHER_FAULT; 
	}

}
