package gov.cdc.izgateway.dynamodb;
import java.text.ParseException;
import java.util.Date;

import org.apache.commons.lang3.time.FastDateFormat;

import gov.cdc.izgateway.common.Constants;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * @author Audacious Inquiry
 * A converter for the java.util.Date time which converts dates to and from
 * strings in the a ISO 8601 timestamp format using the UTC timezone.
 */
public class DateConverter implements AttributeConverter<Date> { // NOSONAR, singleton OK here.
	private static FastDateFormat ft = FastDateFormat.getInstance(Constants.TIMESTAMP_FORMAT);
	private static final DateConverter INSTANCE = new DateConverter();
	
	@Override
	public AttributeValue transformFrom(Date input) {
		return AttributeValue.fromS(convert(input));
	}

	@Override
	public Date transformTo(AttributeValue input) {
		try {
			return ft.parse(input.s());
		} catch (ParseException e) {
			return null;
		}
	}

	@Override
	public EnhancedType<Date> type() {
		return EnhancedType.of(Date.class);
	}

	@Override
	public AttributeValueType attributeValueType() {
		return AttributeValueType.S;
	}
	
	/**
	 * @param <T> The enhanced type
	 * @param enhancedType	A type to attempt conversion on
	 * @return	A converter to use for that class.
	 */
	public static <T> AttributeConverter<T> provider(EnhancedType<T> enhancedType) {
		if (!Date.class.isAssignableFrom(enhancedType.rawClass())) {
			return null;
		}
		@SuppressWarnings("unchecked")
		AttributeConverter<T> result = (AttributeConverter<T>) INSTANCE;
		return result;
	}

	/**
	 * Convert a date to an ISO 8601 String
	 * @param date The date to convert
	 * @return	The string in ISO 8601 format YYYY-MM-DDThh:mm:ss.SSSZ
	 */
	public static String convert(Date date) {
		return ft.format(date);
	}
}
