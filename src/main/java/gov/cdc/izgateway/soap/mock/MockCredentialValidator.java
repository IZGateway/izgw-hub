package gov.cdc.izgateway.soap.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.message.HasCredentials;
import gov.cdc.izgateway.soap.message.HasFacilityID;
import gov.cdc.izgateway.soap.message.HasHL7Message;

public class MockCredentialValidator {
	private MockCredentialValidator() {}
	public static void validateCredential(String expected, String actual, Runnable failure) {
		if (!StringUtils.isEmpty(expected) && !expected.equals(actual)) {
			failure.run();
		}
	}

	public static void checkCredentials(IMessageHeaderService mshService, HasCredentials s) throws SecurityFault {
		String[] mshParts = null;
		if (mshService == null) {
			return;
		}
		if (s instanceof HasHL7Message hl7) {
			mshParts = hl7.getHl7Message().split("\\|");
		} else {
			return;
		}
		
		List<String> headers = Arrays.asList(mshParts.length > 2 ? mshParts[2] : "", mshParts.length > 3 ? mshParts[3] : "");
		List<IMessageHeader> h = mshService.getMessageHeaders(headers);
		if (h != null && !h.isEmpty()) {
			IMessageHeader hdr = h.get(0);
			List<String> failures = new ArrayList<>();

			validateCredential(hdr.getUsername(), s.getUsername(),
					() -> failures.add("Invalid Username"));
			validateCredential(hdr.getPassword(), s.getPassword(),
					() -> failures.add("Invalid Password"));
			if (s instanceof HasFacilityID f) {
				validateCredential(hdr.getFacilityId(), f.getFacilityID(),
						() -> failures.add("Invalid FacilityID"));
			}
			if (!failures.isEmpty()) {
				throw SecurityFault.generalSecurity("User Credential Error", failures.toString(), null);
			}
		}
	}

}
