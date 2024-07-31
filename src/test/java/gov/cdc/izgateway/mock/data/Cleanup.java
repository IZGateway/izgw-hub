package gov.cdc.izgateway.mock.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorMockIIS;

/**
 * Tools for cleaning up or otherwise manipulating the HL7 test message data.
 * 
 * @author Audacious Inquiry
 */
class Cleanup {
	public static void main(String ... args) throws IOException {
		cleanup();
	}
	static void cleanup()throws IOException {
		String filename = PerformanceSimulatorMockIIS.MOCK_DATA_FILE;
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(Cleanup.class.getClassLoader().getResourceAsStream(filename), 
						StandardCharsets.UTF_8))
		) {
			String line;
			String orc = "ORC|RE||65930^DCS";
			int counter = 0;
			while ((line = r.readLine()) != null) {
				counter++;
				if (line.startsWith(orc)) {
					line = String.format("ORC|RE||2%08d^DCS", counter) + line.substring(orc.length());
				} else if (line.startsWith("PID|1||")) {
					line = String.format("PID|1||1%08d^^^MYEHR^MR||", counter) + StringUtils.substringAfter(line, "^^^MYEHR^MR||");
					// Make the phone number clearly fake using 999 area code.
					line = line.replace("{{Telephone Number}}", String.format("(999)457-%04d", counter % 10000));
					line = line.replace("|ASIAN|", pickRace(counter));
					line = line.replace("|not HISPANIC", pickEthnicity(counter));
				} else if (line.startsWith("MSH|")) {
					line = line.replace("{{Unique Message Identifier}}", String.format("9%08d", counter));
				}
				System.out.println(line);
			}
		}
	}

	// Using legacy codes instead of CDC codes to test conversions from legacy
	private static CharSequence pickEthnicity(int counter) {
		return counter % 5 == 0 ? "|HISPANIC" : "|not HISPANIC";
	}

	private static CharSequence pickRace(int counter) {
		switch (counter % 20) {
		case 19: return "|HAWAIIAN|";
		case 18: return "|INDIAN|";
		case 16, 17: return "|ASIAN|";
		case 14, 15: return "|OTHER|";
		case 11, 12, 13: return "|BLACK|";
		default: return "|WHITE|";
		}
	}
}
