package gov.cdc.izgateway.elastic;

/**
 * Thrown when destination status cannot be read from Elasticsearch.
 * <p>
 * A reset clears a circuit breaker held in the status cache. An empty cache that was
 * filled by a successful read holds no thrown breaker, so a reset against it succeeds
 * and has nothing to do. An empty cache left by a failed read says nothing about the
 * breakers, so a reset against it must not report success. See IGDD-3599.
 * </p>
 * <p>
 * An instance with no Elasticsearch endpoint configured reads an empty status set
 * successfully and does not raise this.
 * </p>
 *
 * @author Audacious Inquiry
 */
public class StatusUnavailableException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	/**
	 * Create the exception.
	 *
	 * @param message	What went wrong. This text becomes the outcome reported for the
	 *					instance, so it must read as an outcome
	 */
	public StatusUnavailableException(String message) {
		super(message);
	}
}
