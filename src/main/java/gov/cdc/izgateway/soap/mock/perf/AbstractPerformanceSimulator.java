package gov.cdc.izgateway.soap.mock.perf;


import org.apache.catalina.connector.ResponseFacade;
import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.net.NioEndpoint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.soap.MockMessage;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.fault.UnexpectedExceptionFault;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import gov.cdc.izgateway.utils.ReflectionUtils;
import gov.cdc.perf.histogram.ArrayHistogram;
import gov.cdc.perf.histogram.Histogram;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public
abstract class AbstractPerformanceSimulator  implements PerformanceSimulatorInterface {
	protected AbstractPerformanceSimulator() {
		initErrors();
	}
    protected interface Callable1<T extends Object> {
        ResponseEntity<String> call(T t) throws Fault;
    }
    public interface Simulator {
	    ResponseEntity<String> simulate(HttpServletResponse resp) throws Fault;
	    public int getFrequency();
    }
	/** The test case/MSH-6 value to use to trigger use of this simulator */
    static final String PERF = "PERF";
    /**
     * The number of milliseconds used for each bucket
     * in the DELAYS table.
     */
    public static final int DELAY_UNIT = 250;
    /**
     * The quantity of delay to assume is already present due to existing
     * network roundtrip overhead between IZGW and this mock. This should
     * be determined by experiment, but is assumed to be 50ms for now.
     *
     * This should account for compute setting up the connection.
     */
    public static final int OVERHEAD = 50;
    /**
     * Delay assumed to be in front end server when service
     * is otherwise unavailable or has an early fault response
     */
    private static final int FRONTEND_DELAY = DELAY_UNIT - OVERHEAD;
    /**
     * Data below represents the IIS Timing Response Profile
     * Out of 1000 requests, 162 complete in 250ms or less,
     * 303 in 250-500ms, et cetera.  Can be used to interpolate
     * a random delay response.
     */
    public static final Histogram DELAYS = new ArrayHistogram(
            Arrays.asList(
                    0, 162, 303, 340, 375, 427, 478, 530,
                    575, 622, 663, 707, 745, 773, 800, 827,
                    847, 865, 881, 895, 909, 921, 931, 940,
                    948, 954, 960, 964, 969, 972, 976, 979,
                    982, 985, 988, 990, 992, 995, 998, 999),
            0, DELAY_UNIT, true);

    /*
     * About the tables below:
     * Each table describes buckets in a histogram.  They contain a list of values in a 3 element array,
     * where a[0] is the low range (inclusive),  a[1] is the high range [exclusive], and a[2] is the
     * number of times an event occured with a value in that range.
     */
    /**
     * Data below represents IIS Response Sizes for QBP messages.
     */
    private static final int[][] _QBP_SIZES = {
            { 0, 250, 14265 },
            { 250, 500, 77162 },
            { 500, 750, 61135 },
            { 750, 1000, 42919 },
            { 1000, 1250, 40622 },
            { 1250, 1500, 69463 },
            { 1500, 1750, 51406 },
            { 1750, 2000, 49635 },
            { 2000, 2500, 17642 },
            { 2500, 3000, 12804 },
            { 3000, 3500, 17569 },
            { 3500, 4000, 15892 },
            { 4000, 4500, 8619 },
            { 4500, 5000, 1198 },
            { 5000, 6000, 4822 },
            { 6000, 7000, 13791 },
            { 7000, 8000, 12981 },
            { 8000, 9000, 3195 },
            { 9000, 10000, 2544 },
            { 10000, 11000, 11087 },
            { 11000, 12000, 3087 },
            { 12000, 13000, 12522 },
            { 13000, 14000, 1723 },
            { 14000, 15000, 5532 },
            { 15000, 19000, 7377 },
            { 19000, 34000, 8629 },
            { 34000, 58000, 242 },
            { 58000, 64000, 4 },
            { 64000, 698000, 0 },
            { 698000, 702000, 679 },
            { 702000, 2980000, 0 },
            { 2980000, 3020000, 529 }
    };
    public static final Histogram QBP_SIZES = new ArrayHistogram(_QBP_SIZES);

    /**
     * Data below represents IIS Request Sizes for VXU requests.
     */
    private static final int[][] _VXU_REQUEST_SIZES = {
            { 0, 250, 0 },
            { 250, 500, 1115 },
            { 500, 750, 1014 },
            { 750, 1000, 11067 },
            { 1000, 1250, 23392 },
            { 1250, 1500, 34685 },
            { 1500, 1750, 2006 },
            { 1750, 2000, 12841 },
            { 2000, 2500, 18193 },
            { 2500, 3000, 11836 },
            { 3000, 3500, 7277 },
            { 3500, 4000, 15583 },
            { 4000, 4500, 20719 },
            { 4500, 5000, 12538 },
            { 5000, 6000, 27765 },
            { 6000, 7000, 26904 },
            { 7000, 8000, 2232 },
            { 8000, 9000, 5990 },
            { 9000, 10000, 6318 },
            { 10000, 11000, 189 },
            { 11000, 12000, 4990 },
            { 12000, 13000, 14465 },
            { 13000, 14000, 5547 },
            { 14000, 15000, 99 },
            { 15000, 19000, 4094 },
            { 19000, 34000, 9796 },
            { 34000, 58000, 1104 }
    };
    public static final Histogram VXU_REQUEST_SIZES = new ArrayHistogram(_VXU_REQUEST_SIZES);

    private static AtomicLong messageNumber = new AtomicLong();
    private static final String HOSTNAME = getHostname();

    protected List<Simulator> errors = null;
    protected int maxErrorValue;
    private static final String QBP_FAIL1 = getMessage(MockMessage.TC_ACK04).getHl7Message();
    private static final String QBP_FAIL2 = getMessage(MockMessage.TC_ACK06).getHl7Message();
    private static final String QBP_SUCCESS = getMessage(MockMessage.TC_04).getHl7Message();
    private static final String[] QBP_PATIENT = {
            "PID|1||123456^^^MYEHR^MR~987633^^^MYIIS^SR||Child^Robert^Quenton^^^^L|Que^Suzy^^^^^M|||||10 East Main St^^Myfaircity^GA\r",
            "PD1||||||||||||N|20091130\r",
            "NK1|1|Child^Suzy^^^^^L|MTH^Mother^HL70063\r"
    };
    private static final String[] QBP_VAX = {
            "ORC|RE||142324567^YOUR_EHR|||||||^Shotgiver^Fred||^Orderwriter^Sally^^^^^^^^^^^^^^^^^^MD\n",
            "RXA|0|1|20050725||03^MMR^CVX|0.5|mL^^UCUM||00^New Immunization Record^NIP001\n",
            "RXR|SC^^HL70162\n"
    };
    private static final List<String> QBP_SUCCESS_PARTS = new ArrayList<>();
    static {
        QBP_SUCCESS_PARTS.addAll(Arrays.asList(QBP_SUCCESS));
        QBP_SUCCESS_PARTS.addAll(Arrays.asList(QBP_PATIENT));
    }

    private static final SubmitSingleMessageResponse VXU_FAIL1 = getMessage(MockMessage.TC_ACK03);
    private static final SubmitSingleMessageResponse VXU_FAIL2 = getMessage(MockMessage.TC_ACK05);
    private static final SubmitSingleMessageResponse VXU_SUCCESS = getMessage(MockMessage.TC_ACK02);

    /** Random Number Generators */
    static final Random RAND_QBP = new Random();
    static final Random RAND_VXU = new Random();
    static final Random RAND_ERROR = new Random();
    static final Random RAND_DELAY = new Random();
    static final Random RAND_DATA = new Random();

    abstract void initErrors();
    protected int getSampleSize() {
    	return 1000000;
    }
    
    public static void setSeed(long l) {
        RAND_QBP.setSeed(l);
        RAND_VXU.setSeed(l);
        RAND_ERROR.setSeed(l);
        RAND_DELAY.setSeed(l);
        RAND_DATA.setSeed(l);
    }

    /**
     * Get the hostname for reporting in MSH-4
     * @return The hostname.
     */
    private static String getHostname() {
        String hostname = null;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
        	// Ignore this error
        }
        if (hostname == null || hostname.length() == 0) {
            String[] env = { "HOST", "COMPUTERNAME", "HOSTNAME" };
            for (String v: env) {
                hostname = System.getenv(v);
                if (hostname != null && hostname.length() != 0) {
                    break;
                }
            }
        }
        return hostname == null ? UUID.randomUUID().toString() : hostname;
    }
    
    /*
     * Simulate an error at random intervals.
     * @return true if an error was simulated.
     */
    public final ResponseEntity<String> simulateError(HttpServletResponse resp) throws Fault  {

    	int value = RAND_ERROR.nextInt(getSampleSize());
        if (value > maxErrorValue ) {
            return null;
        }
        for (Simulator errorCase : errors) {
            if (value < errorCase.getFrequency()) {
                return errorCase.simulate(resp);
            }
            value -= errorCase.getFrequency();
        }
        // Shouldn't ever happen (b/c of maxErrorValue exit above), but if it does, treat as no error
        return null;
    }
    
    private static SubmitSingleMessageResponse getMessage(MockMessage msg) {
        try {
            return (SubmitSingleMessageResponse) msg.getMessage(null).getBody();
        } catch (Fault ex) {
            // This is not happening
        	log.error(Markers2.append(ex), "This is not happening");
            throw new RuntimeException("Unexpected Exception", ex);
        }
    }

    /**
     * Given an inbound message, return an appropriately sized response.
     * @return An appropriate response
     */
    @Override
    public SubmitSingleMessageResponse getResponse(SubmitSingleMessageRequest requestMessage) {
    	String hl7RequestMessage = requestMessage.getHl7Message();
        insertDelay();
        SubmitSingleMessageResponse result;
        String messageType = getMessageType(hl7RequestMessage);
        switch (messageType) {
        case "QBP":
            result = new SubmitSingleMessageResponse(getQBPMessage());
            break;
        case "VXU":
            switch (RAND_VXU.nextInt(100)) {
                case 0:
                    result = VXU_FAIL1;
                    break;
                case 1:
                    result = VXU_FAIL2;
                    break;
                default:
                    result = VXU_SUCCESS;
                    break;
            }
            break;
        default:
            result = getMessage(MockMessage.TC_07);
            break;
        }
        assert result != null; 
        // Adjust message to that MSH-4 contains hostname (for diagnostics of mock servers)
        // and MSH-10 contains message number.  Without this, we cannot easily trace which
        // mock in a cluster sent a given response.
        String message = result.getHl7Message();
        if (message != null) {
            String[] parts = message.split("\\|");
            if (parts.length < 4) {
                return result;
            } else {
                parts[3] = HOSTNAME;
            }
            if (parts.length < 10) {
                parts[9] = Long.toUnsignedString(messageNumber.incrementAndGet());
            }
            message = StringUtils.join(parts, '|');
            result.setHl7Message(message);
        }
        return result;
    }

    private String getMessageType(String message) {
		if (message.contains("|QBP^")) {
			return "QBP";
		}
		if (message.contains("|VXU^")) {
			return "VXU";
		}
		return "other";
	}

	/**
     * Return a QBP response message of random length according to selected distribution.
     * @return a QBP response message of random length according to selected distribution.
     */
    private static String getQBPMessage() {
        int len = 0;
        boolean fail = false;
        // Synchronize access to random stream of bits for repeatability
        synchronized (RAND_QBP) {
            len = QBP_SIZES.randomValue(RAND_QBP);
            fail = RAND_QBP.nextInt(100) < 2;
        }
        // Failure is not an option for very large transmissions. Those
        // will always be PDF transmissions.
        if (len < 65536 && fail) {
            if (Math.abs(QBP_FAIL1.length() - len) < 125) {
                return QBP_FAIL1;
            }
            if (Math.abs(QBP_FAIL2.length() - len) < 125) {
                return QBP_FAIL2;
            }
        }

        StringBuilder message = new StringBuilder();
        int i = 0;
        do {
            message.append(QBP_SUCCESS_PARTS.get(i));
        } while (++i < QBP_SUCCESS_PARTS.size() && message.length() < len);

        if (len >= 65536) {
            // Simulate a large PDF for anything over 64K
            message.append("OBX|1|ED|DOC^Document^L||^application^pdf^Base64^");
            len -= message.length() + 1;
            len += 3;   // Rounding to nearest 4
            len /= 4;   // 4 characters per 3 bytes of data.

            // len now contains the number of 3 byte chunks to be Base64 encoded
            byte[] data = new byte[len * 3];
            // Assume a compressed PDF file, so populate data with random bytes
            // (compressed data is generally nearly random).
            RAND_DATA.nextBytes(data);
            message.append(Base64.getEncoder().encode(data)).append("\r");

            return message.toString();
        }
        while (message.length() < len) {
            for (String vax: QBP_VAX) {
                message.append(vax);
                if (message.length() >= len) {
                    // Attempt to match expected length
                    break;
                }
            }
        }

        return message.toString();
    }
    
    static ResponseEntity<String> simulateTLSConnectionError(HttpServletResponse resp) {
        resetConnection(resp);
        return message("Simulated TLS Connection Error");
    }
    
	static ResponseEntity<String> message(String message) {
        return new ResponseEntity<>(message, (HttpHeaders)null, 420);
    }

    static ResponseEntity<String> simulateReadTimeout(HttpServletResponse resp) {
        try {
            // Start writing to ensure that we get a read timeout
            resp.getWriter().print("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">");
            resp.getWriter().flush();
        } catch (IOException e) {
            // Don't care
        }
        sleep(63000);
        return message("Simulated Read Timeout");
    }

    private static void resetConnection(HttpServletResponse resp) {
        resp = ReflectionUtils.unwrapResponse(resp);
        if (resp instanceof ResponseFacade rf) {
            try {
                // Drill through Tomcat internals using reflection to get to the underlying socket and SHUT it down hard.
                NioEndpoint.NioSocketWrapper w = ReflectionUtils.getField(rf, "response.coyoteResponse.hook.socketWrapper", NioEndpoint.NioSocketWrapper.class);
                ReflectionUtils.attempt(() -> w.getSocket().getIOChannel().shutdownInput());
                ReflectionUtils.attempt(() -> w.getSocket().getIOChannel().shutdownOutput());
            } catch (SecurityException | IllegalArgumentException | NoSuchFieldException | IllegalAccessException e) {
                log.warn(Markers2.append(e), "Cannot access socket: {}", e);
            }
        } else {
        	SocketException sex = new SocketException("Connection Reset [simulated]");
        	log.info(Markers2.append(sex), "Simulated Socket Reset");
            throw new RuntimeException("Simulated Socket Reset", sex);
        }
    }
    static ResponseEntity<String> simulateConnectTimeout(HttpServletResponse resp) {
        PrintWriter pw;
        try {
            pw = resp.getWriter();
        } catch (IOException e) {
            // Ignore IO Errors
            pw = new PrintWriter(Writer.nullWriter());
        }
        // Simulate Connect by sleeping 1/2/4/8 seconds and then reset
        // Similar to Connect timeout, forces reciever to wake up to read bytes
        sleep(1000);
        pw.write(' ');
        pw.flush();
        sleep(2000);
        pw.write(' ');
        pw.flush();
        sleep(4000);
        pw.write(' ');
        pw.flush();
        sleep(8000);
        resetConnection(resp);
        return message("Simulated Connection Timeout");
    }
    static ResponseEntity<String> simulateConnectionRefused(HttpServletResponse resp) {
        resetConnection(resp);
        return message("Simulated Connection Refused");
    }
    static ResponseEntity<String> simulateConnectionReset(HttpServletResponse resp) {
        insertDelay();
        resetConnection(resp);
        return message("Simulated Connection Reset");
    }
    static ResponseEntity<String> simulateHttp403(HttpServletResponse resp) {
        sleep(FRONTEND_DELAY);
        return simulateHttpError(resp, HttpServletResponse.SC_FORBIDDEN);
    }
    static ResponseEntity<String> simulateHttpError(HttpServletResponse resp, int code) {
        try {
            resp.sendError(code);
        } catch (IOException e) {
            // Ignore IO Exception
        }
        return message("Simulated HTTP " + code + " Error");
    }

    static ResponseEntity<String> simulateHttp404(HttpServletResponse resp) {
        sleep(FRONTEND_DELAY);
        return simulateHttpError(resp, HttpServletResponse.SC_NOT_FOUND);
    }
    static ResponseEntity<String> simulateHttp408(HttpServletResponse resp) {
        insertDelay();
        return simulateHttpError(resp, HttpServletResponse.SC_REQUEST_TIMEOUT);
    }
    static ResponseEntity<String> simulateHttp502(HttpServletResponse resp) {
        sleep(FRONTEND_DELAY);
        return simulateHttpError(resp, HttpServletResponse.SC_BAD_GATEWAY);
    }
    static ResponseEntity<String> simulateHttp503(HttpServletResponse resp) {
        sleep(FRONTEND_DELAY);
        return simulateHttpError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
    static ResponseEntity<String> simulateEndOfFile(HttpServletResponse resp) {
        try {
            resp.getWriter().write("<soap:");
            resp.getWriter().flush();
            insertDelay();
        } catch (IOException e) {
            // Ignore this
        }
        resetConnection(resp);
        return message("Simulated End of File Error");
    }
    
    static ResponseEntity<String> simulateAuthenticationError(HttpServletResponse resp) throws SecurityFault {
        sleep(FRONTEND_DELAY);
        // This method is here to simplify detection of error type in testing.
        securityFault();
        return message("Simulated Authentication Error");
    }

    static void securityFault() throws SecurityFault {
        throw SecurityFault.generalSecurity("Invalid Username, Password or FacilityID", null, null);
    }
    static ResponseEntity<String> simulateControlChars(HttpServletResponse resp) throws UnexpectedExceptionFault {
        sleep(FRONTEND_DELAY);
        simulateFault("Invalid characters in XML");
        return message("Simulated Invalid characters in XML");
    }
    static ResponseEntity<String> simulateNoRunningCommunicationPoint(HttpServletResponse resp) 
    	throws UnexpectedExceptionFault 
    {   
    	// Front ends typically wait about 5-10s to get a connection from a connection pool
        sleep(5000);
        simulateFault("No Running Communication Point");
        return message("Simulated No Running Communication Point");
    }
    static ResponseEntity<String> simulateOtherFault(HttpServletResponse resp) throws UnexpectedExceptionFault {
        sleep(FRONTEND_DELAY);
        simulateFault("Unknown Fault");
        return message("Simulated Unknown Fault");
    }

    public static ResponseEntity<String> simulateFault(String message) throws UnexpectedExceptionFault {
    	throw new UnexpectedExceptionFault("Simulated Fault", new Exception("Simulated Fault"), "User requested that a fault be generated for interface testing.");
    }

    /**
     * Insert a random delay that follows the typical response curve for IIS.
     * @return  A the amount of time that was simulated for the delay.
     */
    static int insertDelay() {

        int random = 0;
        int sleep = 0;
        int totalDelay = 0;
        // Ensure that random stream of delays is identical
        synchronized (RAND_DELAY) {
            random = RAND_DELAY.nextInt(DELAYS.getTotal() + 2);
            sleep = RAND_DELAY.nextInt(50000);
            totalDelay = DELAYS.randomValue(RAND_DELAY);
        }


        // Handle the long tail as a uniform distribution
        // over 10s - 60s range.
        if (random == DELAYS.getTotal() + 1) {
            sleep(sleep);
            return random;
        }
        if (totalDelay > 0) {
            sleep(totalDelay);
        }
        return totalDelay + OVERHEAD;
    }

    private static int sleep(int delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return delay;
    }    
}
