package gov.cdc.izgateway.elastic;

/**
 * Thrown when the list of running instances cannot be read from Elasticsearch.
 * <p>
 * An empty host list and a failed read are different answers. A caller that
 * distributes a command to every instance must not treat a failed read as proof that
 * no other instance exists. See IGDD-3599.
 * </p>
 * <p>
 * A repository that is not configured with an Elasticsearch endpoint still returns an
 * empty list rather than throwing. Host reporting is switched off in that case, which
 * is a deployment choice and not a fault.
 * </p>
 *
 * @author Audacious Inquiry
 */
public class HostDiscoveryException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	/**
	 * Create the exception.
	 *
	 * @param message	What went wrong
	 * @param cause	The underlying I/O or TLS failure
	 */
	public HostDiscoveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
