package gov.cdc.izgateway.mock.perf;

import java.io.UnsupportedEncodingException;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.soap.MockMessage;
import gov.cdc.izgateway.soap.fault.MessageTooLargeFault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.message.FaultMessage;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;

class TestMockMessageHandling extends TestMockBase {
    private final String HL7_MESSAGE_FORMAT = "MSH|^~\\&|UNIT|TESTING|%s|%s|20211215140314-0500||ACK^V04^ACK|99999999999999|P|2.5.1|||NE|NE|||||Z23^CDCPHINVS|\r";
    TestMockMessageHandling() throws Exception {
    }

    /**
     * Test all of the mock messages
     * @param testCase  The mock to test
     * @throws MessageTooLargeFault If the mock throws a MessageTooLargeFault
     * @throws SecurityFault    If the mock throws a SecurityFault
     * @throws UnsupportedEncodingException On an encoding error retrieving content
     */
    @ParameterizedTest
    @EnumSource(value=MockMessage.class, names= { "TC_13C", "TC_13E" }, mode=Mode.EXCLUDE)
    void testMockMessage(MockMessage testCase) throws MessageTooLargeFault, SecurityFault, UnsupportedEncodingException {
        Throwable exception = null;
        ResponseEntity<?> result = null;;

        String hl7Message = String.format(HL7_MESSAGE_FORMAT, "TEST", testCase);
        SubmitSingleMessageRequest request = new SubmitSingleMessageRequest(hl7Message);
        try {
            RequestContext.init();
        	result = service.submitSoapRequest(request, null);
        	if (result.getBody() instanceof SubmitSingleMessageResponse) {
        		// Do nothing
        	} else if (result.getBody() instanceof FaultMessage fm) {
        		exception = fm.getFault();
        	} else {
        		throw new Exception("Unexpected Response: " + result.getBody().toString());
        	}
        } catch (Throwable e) {
            exception = e;
        } finally {
        	RequestContext.clear();
        }
        checkedExpected(testCase, result, exception);
    }

    void testTimeOut() throws UnsupportedEncodingException, MessageTooLargeFault, SecurityFault {
        long timer = -System.currentTimeMillis();
        testMockMessage(MockMessage.TC_FORCE_TIMEOUT);
        timer += System.currentTimeMillis();
        Assertions.assertTrue(timer > 60000);
    }

    private void checkedExpected(MockMessage testCase, ResponseEntity<?> result, Throwable exception) throws UnsupportedEncodingException {
        Object expected = testCase.getExpected();
        String content = result.getBody().toString();
        if (expected instanceof SubmitSingleMessageRequest) {
            Assertions.assertEquals(expected, result);
        } else if (expected instanceof Throwable) {
        	System.err.println(content);
        	Object body = result.getBody();
        	if (body instanceof FaultMessage fm) {
        		Assertions.assertEquals(expected.getClass().getSimpleName(), fm.getFault().getClass().getSimpleName());
	            Assertions.assertEquals(((Throwable) expected).getMessage(), fm.getReason());
        	} else {
	            Assertions.assertEquals(expected.getClass(), exception.getClass());
	            Assertions.assertEquals(((Throwable) expected).getMessage(), exception.getMessage());
        	}
        } else if (expected instanceof HttpStatus) {
            Assertions.assertEquals(((HttpStatus) expected).value(), result.getStatusCode().value());
        } else if (expected instanceof ResponseEntity<?>) {
            @SuppressWarnings("unchecked")
            ResponseEntity<String> ent = (ResponseEntity<String>) expected;
            Assertions.assertEquals(ent.getBody(), content);
            Assertions.assertEquals(ent.getHeaders().getContentType().toString(), result.getHeaders().getContentType().toString());
            Assertions.assertEquals(ent.getStatusCode().value(), result.getStatusCode().value());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "Bad|Bad", "Bad|TC_01", "TEST|Bad", "TEST|", "TEST|TC_99", "|", " | " })
    void testNotAMockMessage(String values) throws MessageTooLargeFault, SecurityFault {
        String msh5 = StringUtils.substringBefore(values, "|");
        String msh6 = StringUtils.substringAfter(values, "|");

        String hl7Message = String.format(HL7_MESSAGE_FORMAT, msh5, msh6);
        runSubmitSingleMessageTest(hl7Message);
    }

    @Test
    void testNotAnHL7Message() throws MessageTooLargeFault, SecurityFault {
        runSubmitSingleMessageTest("Not an HL7 Message");
    }

    private void runSubmitSingleMessageTest(String hl7Message) throws MessageTooLargeFault, SecurityFault {
        SubmitSingleMessageRequest request = new SubmitSingleMessageRequest(hl7Message);
        SubmitSingleMessageResponse response = null;
    	RequestContext.init();
    	try {
	        response = (SubmitSingleMessageResponse) service.submitSoapRequest(request,  null).getBody();
    	} finally {
    		RequestContext.clear();
    	}
    	Assertions.assertNotNull(response);
        Assertions.assertEquals(hl7Message, response.getHl7Message());
    }
}
