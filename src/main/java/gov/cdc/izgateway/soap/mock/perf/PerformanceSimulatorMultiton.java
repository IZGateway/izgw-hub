package gov.cdc.izgateway.soap.mock.perf;

/**
 * This class uses the Multiton pattern to return a PerformanceSimulator given the specified performanceProfileIdentifier.
 */
public class PerformanceSimulatorMultiton {
	private PerformanceSimulatorMultiton () {}
    static final String PERFORMANCE_PROFILE_TYPICAL = "PERF";
    static final String PERFORMANCE_PROFILE_REPEATED_FAILURES = "PERF_REPEATED_FAILURES";
    static final String PERFORMANCE_PROFILE_REGIONAL_OUTAGE = "PERF_REGIONAL_OUTAGE";
    public static final String PERFORMANCE_PROFILE_MOCK_IIS = "MOCK";
    
    private static final PerformanceSimulatorInterface performanceSimulatorDailyFailures = new PerformanceSimulatorDailyFailures();
    private static final PerformanceSimulatorInterface performanceSimulatorRepeatedFailures = new PerformanceSimulatorRepeatedFailures();
    private static final PerformanceSimulatorInterface performanceSimulatorRegionalOutage = new PerformanceSimulatorRegionalOutage();
    private static final PerformanceSimulatorInterface mockIis = new PerformanceSimulatorMockIIS();
    public static PerformanceSimulatorInterface getInstance(String performanceProfileIdentifier) {
    	if (performanceProfileIdentifier == null) {
    		return null;
    	}
        switch (performanceProfileIdentifier) {
        case PERFORMANCE_PROFILE_TYPICAL:
            return performanceSimulatorDailyFailures;
        case PERFORMANCE_PROFILE_REPEATED_FAILURES:
            return performanceSimulatorRepeatedFailures;
        case PERFORMANCE_PROFILE_REGIONAL_OUTAGE:
            return performanceSimulatorRegionalOutage;
        case PERFORMANCE_PROFILE_MOCK_IIS:
        	return mockIis;
        default:
            return null;
        }
    }
}