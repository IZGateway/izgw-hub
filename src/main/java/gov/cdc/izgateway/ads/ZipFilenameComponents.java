package gov.cdc.izgateway.ads;

import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.time.FastDateFormat;

import lombok.Data;

/**
 * Parses and validates ZIP (routine immunization) filenames for ADS submissions.
 * <p>
 * ZIP filenames follow the format {@code EEE_YYYYMMDD_yyyymmdd_Z.zip}
 * (entity_startdate_submissiondate_zone). This class also detects the "test" keyword
 * in any filename to set the {@link #testfile} flag.
 * </p>
 * <p>
 * CSV filename validation is handled separately by
 * {@link gov.cdc.izgateway.ads.util.CsvFilenameValidator}.
 * </p>
 */
@Data
class ZipFilenameComponents {
	public static final String ROUTINE_IMMUNIZATION = "routineImmunization";
	String entityId = "";
	String submissionDate = "";
	String period = "";
	String filetype = "";
	Date date = null;
	String originalFilename;
	String filename;
	String[] parts;
	List<String> errors;
	boolean formatError = false;
	boolean testfile = false;

	private ZipFilenameComponents(String filename, List<String> errors) {
		if (errors == null) {
			errors = new ArrayList<>();
		}
		this.originalFilename = filename;
		if (Strings.CI.contains(filename, "test")) {
			testfile = true;
			filename = Strings.CI.remove(filename, "test");
		}
		this.filename = filename;
		this.errors = errors;
		parts = StringUtils.substringBeforeLast(filename, ".").split("_");
	}

	/**
	 * Parse a ZIP filename. CSV validation is delegated to
	 * {@link gov.cdc.izgateway.ads.util.CsvFilenameValidator} and should not be
	 * passed here.
	 *
	 * @param filename the filename to parse
	 * @param errors   list to which any validation errors are appended
	 * @return the parsed result (testfile flag is always populated regardless of extension)
	 */
	static ZipFilenameComponents parse(String filename, List<String> errors) {
		ZipFilenameComponents parsed = new ZipFilenameComponents(filename, errors);
		String ext = StringUtils.substringAfterLast(filename, ".");
		if (ext.equalsIgnoreCase("zip")) {
			parsed.parseRoutineImmunizationFilename();
		}
		// CSV validation is handled by CsvFilenameValidator; no action needed here.
		return parsed;
	}

	private void parseRoutineImmunizationFilename() {
		entityId = parts.length > 0 ? parts[0] : "";
		if (parts.length < 2 || StringUtils.isEmpty(parts[1])) {
			formatError = true;
		} else {
			period = parts[1];
		}
		if (parts.length < 3 || StringUtils.isEmpty(parts[2])) {
			formatError = true;
		} else {
			submissionDate = parts[2];
		}
		filetype = ROUTINE_IMMUNIZATION;
		if (formatError) {
			errors.add(String.format(
				"Filename (%s) is in wrong format (It is expected to match EEE_YYYYMMDD_yyyymmdd_Z.zip)",
				filename));
		} else {
			date = checkPeriod("yyyyMMdd");
		}
	}

	private Date parseDate(String dateString, String format) {
		FastDateFormat ft = FastDateFormat.getInstance(format);
		try {
			return ft.parse(dateString);
		} catch (ParseException pex) {
			errors.add(String.format("Date (%s) is invalid, it does not match the expected format %s", period, format));
			return null;
		}
	}

	private Date checkPeriod(String format) {
		Date start = parseDate(period, format);
		Date end = StringUtils.isNotEmpty(submissionDate) ? parseDate(submissionDate, format) : null;

		if (end != null && end.before(start)) {
			errors.add(String.format("Dates (%s %s) are invalid, start is after submission date.", period, submissionDate));
		}

		// Fix for Pacific Island Metadata Issue: IGDD-1644
		Date tomorrow = new Date(System.currentTimeMillis() + Duration.ofDays(1).toMillis());
		if ((start != null && tomorrow.before(start)) || (end != null && tomorrow.before(end))) {
			if (end == null) {
				errors.add(String.format("Date (%s) invalid, it cannot be after today", period));
			} else {
				errors.add(String.format("Dates (%s %s) are invalid, they cannot be after today", period, submissionDate));
			}
		}
		return start;
	}
}
