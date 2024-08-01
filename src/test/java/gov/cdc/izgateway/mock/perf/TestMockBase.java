package gov.cdc.izgateway.mock.perf;

import gov.cdc.izgateway.soap.SoapControllerBase;
import gov.cdc.izgateway.soap.mock.MockController2014;

class TestMockBase {

    protected final SoapControllerBase service;
    TestMockBase() {
    	 service = new MockController2014(null, null);
    }
}
