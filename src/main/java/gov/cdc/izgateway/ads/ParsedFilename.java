package gov.cdc.izgateway.ads;

import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;

import lombok.Data;

/** 
 * This class is used to verify that metadata aligns with a filename.
 */
@Data
class ParsedFilename {
	public static final String ROUTINE_IMMUNIZATION = "routineImmunization";
	String entityId = "";
	String submissionDate = "";
	String period = "";
	String filetype = "";
	Date date = null;
	String filename;
	String[] parts;
	List<String> errors;
	boolean formatError = false;
	
	private ParsedFilename(String filename, List<String> errors) {
		if (errors == null) {
			errors = new ArrayList<>();
		}
		this.filename = filename;
		this.errors = errors;
    	parts = StringUtils.substringBeforeLast(filename,".").split("_");
	}
	
	static ParsedFilename parse(String filename, List<String> errors) {
		ParsedFilename parsed = new ParsedFilename(filename, errors);
		String ext = StringUtils.substringAfterLast(filename, ".");
		if (ext.equalsIgnoreCase("zip")) {
			parsed.parseRoutineImmunizationFilename();
		} else if (ext.equalsIgnoreCase("csv")) {
			parsed.parseRiverFilename();
		} else {
			parsed.formatError = true;
			parsed.errors.add(String.format("Filename (%s) is invalid. It must be a CSV or ZIP file.", filename));
		}
		return parsed;
	}
	
	private void parseRiverFilename() {
		filetype = checkRiverFilename(parts);
		entityId = parts.length > 1 ? parts[1] : "";
		period = parts.length > 2 ? parts[2] : "";
		date = checkPeriod("yyyyMMM");
	}
	
	private void parseRoutineImmunizationFilename() {
		// Routine Immunization
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
	    	errors.add(
	    		String.format(
	    			"Filename (%s) is in wrong format (It is expected to match EEE_YYYYMMDD_yyyymmdd_Z.zip)", 
	    			filename)
	    		);
		} else {
			date = checkPeriod("yyyyMMdd");
		}
	}
	private String checkRiverFilename(String[] parts) {
		// Monthly files
		filetype = parts.length > 0 ? parts[0] : "";
		 if (StringUtils.containsIgnoreCase(filetype, "flu")) {
		     filetype = "influenzaVaccination";
		 } else if (StringUtils.containsIgnoreCase(filetype, "rsv")) {
			 filetype = "rsvPrevention";
		 } else if (StringUtils.containsIgnoreCase(filetype, "all")) {
			 filetype = "covidallMonthlyVaccination";
		 } else {
		    filetype = "genericImmunization";
		 }
		 return filetype;
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