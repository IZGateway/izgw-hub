package gov.cdc.izgateway.db;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import gov.cdc.izgateway.db.DbController.DbControllerConfiguration;
import gov.cdc.izgateway.elastic.HostDiscoveryException;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A service to send refresh messages to other instances via SQS and await their responses.
 * 
 * @author Audacious Inquiry
 *
 */
@Slf4j
@Service
public class RefreshQueueService {
    private static final String WAITING_FOR_RESPONSE = "Waiting for Response";
    private static final Map<String, SqsClient> sqsClients = new TreeMap<>();
    private static final List<String> createdQueues = new java.util.ArrayList<>();
	private static final String region = Objects.toString(System.getenv("AWS_REGION"), "unknown");
    private final DbControllerConfiguration configuration;
    private final EndpointStatusService endpointStatusService;
    private final ApiKeyPrincipalProvider apiKeyPrincipalProvider;
    private final IHostRepository hostService;
    private static MessageAttributeValue newStringAttribute(String value) {
    	return MessageAttributeValue.builder().stringValue(value).dataType("String").build();
    }
    private static final MessageAttributeValue nullAttribute = newStringAttribute(null);
    /**
     * Helper to get an attribute from a message, returning null if not present.
     * @param msg	The SQS message
     * @param name	The name of the attribute to get
     * @return	 The attribute value or null if not present
     */
    private static String getAttribute(Message msg, String name) {
		return msg.messageAttributes().getOrDefault(name, nullAttribute).stringValue();
	}
    
    /**
     * A refresh request message to be sent via SQS.
     * 
     * @author Audacious Inquiry
     *
     * @param reset	If true, reset the endpoint after refresh
     * @param eventId	The event ID of the refresh request
     * @param senderHost	The host sending the request
     * @param senderRegion	The region of the host sending the request
     */
    public record RefreshRequest(boolean reset, String eventId, String senderHost, String senderRegion, String jti,
    		boolean refreshConfig, String destId) {
    	public static String MESSAGE = "RefreshRequest";

        /**
         * Create a request that refreshes configuration, matching the original five-field
         * shape. Retained so that existing callers and tests do not change.
         *
         * @param reset	Clear circuit breakers as well as refreshing
         * @param eventId	Correlation identifier
         * @param senderHost	Host to reply to
         * @param senderRegion	Region of the host to reply to
         * @param jti	Credential to evict, or null
         */
        public RefreshRequest(boolean reset, String eventId, String senderHost, String senderRegion, String jti) {
        	this(reset, eventId, senderHost, senderRegion, jti, true, null);
        }

        @Override
        public String toString() {
            return String.format(
                "{\"reset\":%b,\"eventId\":\"%s\",\"senderHost\":\"%s\",\"senderRegion\":\"%s\",\"jti\":\"%s\",\"refreshConfig\":%b,\"destId\":\"%s\"}",
                reset, Objects.toString(eventId, ""), Objects.toString(senderHost, ""), Objects.toString(senderRegion, ""),
                Objects.toString(jti, ""), refreshConfig, Objects.toString(destId, "")
            );
        }

        /**
         * Create a RefreshRequest from an SQS message.
         * <p>
         * An instance running the previous release sends no {@code refreshConfig} and no
         * {@code destId}. Both default to the previous behavior: refresh the
         * configuration, and reset every destination. See IGDD-3599, design decision D3.
         * </p>
         * @param msg	The SQS message
         * @return	 The RefreshRequest
         */
        public static RefreshRequest fromMessage(Message msg) {
			boolean reset = Boolean.parseBoolean(getAttribute(msg, "reset"));
			String eventId = getAttribute(msg, "eventId");
			String senderHost = getAttribute(msg, "senderHost");
			String senderRegion = getAttribute(msg, "senderRegion");
			String jti = getAttribute(msg, "jti");
			String refreshConfigValue = getAttribute(msg, "refreshConfig");
			boolean refreshConfig = refreshConfigValue == null || Boolean.parseBoolean(refreshConfigValue);
			String destId = getAttribute(msg, "destId");
			return new RefreshRequest(reset, eventId, senderHost, senderRegion, jti, refreshConfig, destId);
		}

        /**
         * @return A map of the attributes of the refresh request for use in SQS message attributes
         */
        public Map<String, MessageAttributeValue> toMap() {
        	Map<String, MessageAttributeValue> map = new TreeMap<>();
        	// The names below are read by instances running the previous release. Do not
        	// rename them while a rolling deployment can run mixed versions.
			map.put("reset", newStringAttribute(Boolean.toString(reset)));
			map.put("eventId", newStringAttribute(eventId));
			map.put("senderHost", newStringAttribute(senderHost));
			map.put("senderRegion", newStringAttribute(senderRegion));
			if (jti != null) {
				map.put("jti", newStringAttribute(jti));
			}
			map.put("refreshConfig", newStringAttribute(Boolean.toString(refreshConfig)));
			if (destId != null) {
				map.put("destId", newStringAttribute(destId));
			}
			return map;
        }

        /**
         * @param queueUrl
         * @return A SendMessageRequest with the attributes of this refresh request
         */
        public SendMessageRequest toSendMessageRequest(String queueUrl) {
        	return SendMessageRequest.builder().queueUrl(queueUrl).messageBody(MESSAGE).messageAttributes(toMap()).build();
        }
    }

    /**
     * A refresh response message to be sent via SQS.
     * 
     * @author Audacious Inquiry
     * 
     * @param host	The host sending the response
     * @param region	The region of the host sending the response
     * @param eventId	The event ID of the refresh request being responded to
     * @param status	The status of the refresh operation (e.g., "OK", "Failed")
     */
    public record RefreshResponse(String host, String region, String eventId, String status) {
    	public static String MESSAGE = "RefreshResponse";
        @Override
        public String toString() {
            return String.format(
                "{\"host\":\"%s\",\"region\":\"%s\",\"eventId\":\"%s\",\"status\":\"%s\"}",
                Objects.toString(host, ""), Objects.toString(region, ""), Objects.toString(eventId, ""), Objects.toString(status, "")
            );
        }
        
        /**
		 * Create a RefreshResponse from an SQS message.
		 * @param msg	The SQS message
		 * @return	 The RefreshResponse
		 */
        public static RefreshResponse fromMessage(Message msg) {
        	String host = getAttribute(msg, "host");
        	String region = getAttribute(msg, "region");
        	String eventId = getAttribute(msg, "eventId");
        	String status = getAttribute(msg, "status");
        	return new RefreshResponse(host, region, eventId, status);
        }
        /**
         * @return	 A map of the attributes of the refresh response for use in SQS message attributes
         */
        public Map<String, MessageAttributeValue> toMap() {
			Map<String, MessageAttributeValue> map = new TreeMap<>();
			map.put("host", newStringAttribute(host));
			map.put("region", newStringAttribute(region));
			map.put("eventId", newStringAttribute(eventId));
			map.put("status", newStringAttribute(status));
			return map;
		}
        
        /**
         * Build a SendMessageRequest with the attributes of this refresh Response.
         * @param queueUrl The URL of the SQS queue to send the message to
         * @return A SendMessageRequest with the attributes of this refresh Response
         */
        public SendMessageRequest toSendMessageRequest(String queueUrl) {
        	return SendMessageRequest.builder().queueUrl(queueUrl).messageBody(MESSAGE).messageAttributes(toMap()).build();
        }
    }
    
    /**
     * Create the messaging service.
     * <p>
     * This bean depends on services only. It must never depend on a controller: a
     * controller on the dependency path of the listener thread is what forced the
     * earlier {@code new RefreshQueueService(...)} and kept {@code StatusController}
     * from reaching it. See IGDD-3599.
     * </p>
     *
     * @param configuration          Reloads cached configuration from the datastore
     * @param endpointStatusService  Clears circuit breakers
     * @param apiKeyPrincipalProvider Evicts a cached API key credential
     * @param hostService            Lists the known instances and their regions
     */
    @Autowired
    public RefreshQueueService(
    		DbControllerConfiguration configuration,
    		EndpointStatusService endpointStatusService,
    		ApiKeyPrincipalProvider apiKeyPrincipalProvider,
    		IHostRepository hostService) {
		this.configuration = configuration;
		this.endpointStatusService = endpointStatusService;
		this.apiKeyPrincipalProvider = apiKeyPrincipalProvider;
		this.hostService = hostService;
	}

    /**
     * Create the queues for this instance and start the listener.
     * <p>
     * Spring calls this once for the singleton bean. Two listener threads on one queue
     * would compete for the same messages.
     * </p>
     */
    @PostConstruct
    void start() {
		createRefreshQueues();
		// Add a shutdown hook to delete the created queues
        Runtime.getRuntime().addShutdownHook(new Thread(RefreshQueueService::deleteQueues, "SQS-Queue-ShutdownHook"));
        // Start the refresh loop to listen for refresh requests
		startRefreshListener();
	}
    
	private static SqsClient getClient(String hostRegion) {
		return sqsClients.computeIfAbsent(hostRegion, r -> SqsClient.builder().region(Region.of(r)).build());
	}

	/**
	 * Get the SQS client for a region.
	 * <p>
	 * An instance method wrapping {@link #getClient(String)} so that a test can
	 * substitute a client without reaching AWS. Behavior is unchanged: the same cached
	 * client is returned. See IGDD-3599.
	 * </p>
	 *
	 * @param hostRegion	The region to get a client for
	 * @return The SQS client for that region
	 */
	SqsClient clientFor(String hostRegion) {
		return getClient(hostRegion);
	}

	/**
	 * Get the host name of this instance.
	 * <p>
	 * Every caller must use this method rather than resolving the name itself. The
	 * sender identity on a command and the queue a reply is read from are built from
	 * this value. If two callers disagreed, replies would land on a queue nobody reads
	 * and every remote instance would report a timeout. See IGDD-3599.
	 * </p>
	 *
	 * @return The host name, never null
	 */
	public static String myHostName() {
		// The core resolver is a strict superset of the commons-lang3 one: it reads
		// HOSTNAME, then COMPUTERNAME, then InetAddress, and never returns null. The
		// commons version returns null when HOSTNAME is unset and an empty string when
		// HOSTNAME is set but blank, and an empty string would silently build the queue
		// name izgw__refresh-requests that nobody reads.
		return gov.cdc.izgateway.utils.SystemUtils.getHostname();
	}

	private static void createRefreshQueues() {
		// Create an SQS queue named after the hostname (serverName) to receive refresh messages
		try {
			SqsClient sqsClient = getClient(region);
			String requestQueueName = getRequestQueueName(myHostName());
			String responseQueueName = getResponseQueueName(myHostName());
			createQueue(requestQueueName, sqsClient);
			createQueue(responseQueueName, sqsClient);
		} catch (Exception e) {
			log.error(Markers2.append(e), "Failed to create SQS queues: {}", e.getMessage());
		}
	}

	private static void createQueue(String queueName, SqsClient sqsClient) {
		try {
			sqsClient.getQueueUrl(r -> r.queueName(queueName));
			// This SHOULDN'T happen, but if it does, just log and move on.
			log.warn("SQS queue '{}' already exists", queueName);
		} catch (QueueDoesNotExistException e) {
			sqsClient.createQueue(r -> r.queueName(queueName));
			log.info("Created SQS queue '{}'", queueName);
		}
		createdQueues.add(queueName);
	}

	private static void deleteQueues() {
		try {
		    SqsClient sqsClient = SqsClient.builder().build();
		    for (String queueName : createdQueues) {
		        deleteQueue(sqsClient, queueName);
		    }
		} catch (Exception e) {
		    log.error(Markers2.append(e), "Error during shutdown hook for SQS queue deletion: {}", e.getMessage());
		}
	}

	private static void deleteQueue(SqsClient sqsClient, String queueName) {
		try {
		    String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
		    sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
		    log.info("Deleted SQS queue '{}' on shutdown", queueName);
		} catch (Exception e) {
		    log.error(Markers2.append(e), "Failed to delete SQS queue '{}' on shutdown: {}", queueName, e.getMessage());
		}
	}

    /**
     * Get the SQS queue name for refresh requests for the specified host.
     * @param host	The host name
     * @return	The SQS queue name
     */
    public static String getRequestQueueName(String host) {
        return "izgw_" + host.replace(".", "-") + "_refresh-requests";
    }

    /**
     * Get the SQS queue name for refresh responses for the specified host.
     * @param host	The host name
     * @return	The SQS queue name	
     */
    public static String getResponseQueueName(String host) {
        return "izgw_" + host.replace(".", "-") + "_refresh-responses";
    }
    /**
     * @return The AWS region this instance runs in, or {@code "unknown"} when
     *         {@code AWS_REGION} is not set.
     */
    public static String getRegion() {
    	return region;
    }

    /**
     * How long to wait for replies, in milliseconds.
     * <p>
     * Deliberately not a configuration property. The Configuration Console timeout must
     * stay above this value, and nothing enforces that relationship, so a hardcoded pair
     * cannot drift. See IGDD-3599, design decision D6. Package-private only so that a
     * test does not have to wait 15 seconds.
     * </p>
     */
    static long responseTimeoutMillis = 15000L;

    /** Outcome recorded when this instance cannot read its own reply queue. */
    public static final String INTERNAL_ERROR = "Internal Error";

    /** Outcome recorded when an instance has no queue to receive on. */
    public static final String NO_QUEUE = "No Queue";
    /** Outcome recorded when a message could not be sent. */
    public static final String SEND_FAILURE = "Send Failure";
    /** Outcome recorded when an instance did not reply in time. */
    public static final String TIMED_OUT = "Timed Out";

    /**
     * Key used to report that the list of instances could not be read.
     * <p>
     * Deliberately not a {@code region:hostname} key. It carries no host name, so it is
     * safe to forward to an untrusted client, and {@link #summarizeByRegion} turns it
     * into one more entry in the region summary. A consumer that reads only the region
     * summary still sees the failure. See IGDD-3599.
     * </p>
     */
    public static final String FLEET_KEY = "fleet";

    /** Outcome recorded when the list of instances could not be read. */
    public static final String DISCOVERY_FAILED = "Discovery Unavailable";

    /**
     * Send a command to every known instance other than the caller, then wait for the
     * replies.
     * <p>
     * The caller identifies itself through {@code senderHost} and {@code senderRegion}
     * on the request. Those values decide which entry is skipped, so a caller keeps its
     * own notion of the local host name rather than inheriting one from this class.
     * </p>
     *
     * @param request	The command to send. Its sender fields identify the caller
     * @param results	The outcome map to fill in, keyed {@code region:hostname}
     * @return the same map, with one entry per instance reached
     */
    public Map<String, String> distribute(RefreshRequest request, Map<String, String> results) {
        String me = request.senderHost();
        String myRegion = request.senderRegion();
        Map<String, String> known;
        try {
            known = hostService.getHostsAndRegion();
        } catch (HostDiscoveryException e) {
            // Nothing was sent, and nothing is known about the rest of the fleet. The
            // local reset still stands, so the local entry keeps its outcome, but the
            // caller must not read the whole operation as complete. IGDD-3599.
            log.error(Markers2.append(e, "eventType", "FLEET_DISCOVERY_FAILED",
            		"result", DISCOVERY_FAILED),
            		"Could not list the running instances: {}", e.getMessage());
            results.put(FLEET_KEY, DISCOVERY_FAILED);
            awaitRefreshResponses(request.eventId(), results);
            return results;
        }
        for (Map.Entry<String, String> entry : known.entrySet()) {
            String hostName = entry.getKey();
            String hostRegion = entry.getValue();
            if (hostName.equalsIgnoreCase(me) && hostRegion.equals(myRegion)) {
                continue;  // The caller already applied the command locally
            }
            sendRefreshMessage(request, results, hostName, hostRegion);
        }
        awaitRefreshResponses(request.eventId(), results);
        return results;
    }

    /**
     * Reduce per-instance outcomes to one status per region.
     * <p>
     * The result carries no host name and no instance count, so a consumer can forward
     * it to an untrusted client without redacting it. See IGDD-3599, design decision D5.
     * </p>
     *
     * @param results	Outcomes keyed {@code region:hostname}
     * @return one of {@code complete}, {@code partial} or {@code failed} per region
     */
    public static Map<String, String> summarizeByRegion(Map<String, String> results) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            String key = entry.getKey();
            int colon = key.indexOf(':');
            String regionName = colon < 0 ? key : key.substring(0, colon);
            int[] count = counts.computeIfAbsent(regionName, k -> new int[2]);
            count[1]++;
            if (isSuccess(entry.getValue())) {
                count[0]++;
            }
        }
        Map<String, String> summary = new TreeMap<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int ok = entry.getValue()[0];
            int total = entry.getValue()[1];
            String status = "partial";
            if (ok == total) {
                status = "complete";
            } else if (ok == 0) {
                status = "failed";
            }
            summary.put(entry.getKey(), status);
        }
        return summary;
    }

    private static boolean isSuccess(String outcome) {
        return outcome != null && outcome.startsWith("OK");
    }

    /**
     * Send a refresh request to the specified host in the specified region.
     * @param request The refresh request to send
     * @param results	A map to record the results of the send operation
     * @param host	The host to send the message to
     * @param hostRegion	The region the host is in
     * @return results map with the status of the send operation
     */
    public Map<String, String> sendRefreshMessage(RefreshRequest request, Map<String, String> results, String host, String hostRegion) {
        SqsClient sqsClient = clientFor(hostRegion);
        String endpointName = hostRegion + ":" + host;
        String queueName = getRequestQueueName(host);
        try {
            String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            sqsClient.sendMessage(request.toSendMessageRequest(queueUrl));
        } catch (QueueDoesNotExistException e) {
        	// Usually a task that stopped recently. Elastic still lists it for up to
        	// three minutes after its queue is deleted. Not the same as a real fault.
            log.warn(Markers2.append("eventType", "SQS_SEND_FAILED", "direction", "send",
            		"queueName", queueName, "targetHost", host, "targetRegion", hostRegion,
            		"messageType", RefreshRequest.MESSAGE, "result", NO_QUEUE),
            		"No SQS queue {} for {}", queueName, endpointName);
            results.put(endpointName, NO_QUEUE);
            return results;
        } catch (Exception e) {
            log.error(Markers2.append(e, "eventType", "SQS_SEND_FAILED", "direction", "send",
            		"queueName", queueName, "targetHost", host, "targetRegion", hostRegion,
            		"messageType", RefreshRequest.MESSAGE, "result", SEND_FAILURE),
            		"Exception sending SQS message to {}: {}", queueName, e.getMessage());
            results.put(endpointName, SEND_FAILURE);
            return results;
        }
        log.info(Markers2.append("eventType", "SQS_SEND", "direction", "send",
        		"queueName", queueName, "targetHost", host, "targetRegion", hostRegion,
        		"messageType", RefreshRequest.MESSAGE, "keyId", request.jti()),
        		"SQS send {} to {}", RefreshRequest.MESSAGE, endpointName);
        results.put(endpointName, WAITING_FOR_RESPONSE);
        return results;
    }


    /**
     * Await responses from the refresh messages sent to other instances.
     * @param eventId The event ID of the refresh request
     * @param results	A map of results to update with responses
     * @return results map with the status of the send operation
     */
    public Map<String, String> awaitRefreshResponses(String eventId, Map<String, String> results) {
        if (results.values().stream().noneMatch(WAITING_FOR_RESPONSE::equals)) {
            // Nothing was sent, so there is nothing to wait for and no queue to look up.
            logOperationComplete(eventId, results);
            return results;
        }
        SqsClient sqsClient = clientFor(region);
        String queueName = getResponseQueueName(myHostName());
        String queueUrl;
        try {
            queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        } catch (Exception e) {
            log.error(Markers2.append(e), "Could not get response queue URL: {}", e.getMessage());
            results.replaceAll((k, v) -> v.equals(WAITING_FOR_RESPONSE) ? INTERNAL_ERROR : v);
            // The operation still ended, so it still gets a summary record. Without this
            // an operator searching for SQS_OPERATION_COMPLETE finds nothing at all.
            logOperationComplete(eventId, results);
            return results;
        }
        long timeoutMillis = responseTimeoutMillis;
        long start = System.currentTimeMillis();
        // Replies that answer a different operation on this instance. Held so that one
        // such message produces one log record for this wait, not one record per poll.
        Set<String> logged = new HashSet<>();
        boolean allResponded = results.values().stream().noneMatch(v -> v.equals(WAITING_FOR_RESPONSE));
        while (!allResponded && System.currentTimeMillis() - start < timeoutMillis) {
            try {
                collectReplies(sqsClient, queueUrl, queueName, eventId, results, logged);
            } catch (Exception e) {
                // Polling or deletion failed part way through. Every reply already
                // collected keeps its outcome, and the rest are unknown rather than
                // failed on the remote instance. Throwing here instead would lose the
                // whole map and answer the operator with an HTTP error. IGDD-3599.
                log.error(Markers2.append(e, "eventType", "SQS_RECEIVE_FAILED",
                		"direction", "receive", "queueName", queueName,
                		"messageType", RefreshResponse.MESSAGE, "result", INTERNAL_ERROR),
                		"Could not read response queue {}: {}", queueName, e.getMessage());
                results.replaceAll((k, v) -> v.equals(WAITING_FOR_RESPONSE) ? INTERNAL_ERROR : v);
                logOperationComplete(eventId, results);
                return results;
            }
            allResponded = results.values().stream().noneMatch(v -> v.equals(WAITING_FOR_RESPONSE));
        }
        if (!allResponded) {
			results.replaceAll((k, v) -> v.equals(WAITING_FOR_RESPONSE) ? TIMED_OUT : v);
		}
        logOperationComplete(eventId, results);
        return results;
    }

    /**
     * Read one batch of replies and record the ones that answer this operation.
     *
     * @param sqsClient	Client for this instance's own region
     * @param queueUrl	URL of this instance's response queue
     * @param queueName	Name of that queue, for the log record
     * @param eventId	Correlation identifier of the running operation
     * @param results	The outcome map to update
     * @param logged	Identifiers of foreign replies already recorded in this wait
     */
    private void collectReplies(SqsClient sqsClient, String queueUrl, String queueName, String eventId,
    		Map<String, String> results, Set<String> logged) {
        List<Message> messages = sqsClient.receiveMessage(
        	r -> r.queueUrl(queueUrl)
					.messageAttributeNames("All")
        			.maxNumberOfMessages(10)
        			.waitTimeSeconds(2)
        		).messages();
        for (Message msg : messages) {
        	if (!RefreshResponse.MESSAGE.equals(msg.body())) {
        		continue;
        	}
        	RefreshResponse response = RefreshResponse.fromMessage(msg);
        	boolean answersThisOperation = Objects.equals(eventId, response.eventId());
        	boolean usable = response.host() != null && response.status() != null;
        	if (usable && !answersThisOperation) {
        		releaseForeignReply(sqsClient, queueUrl, queueName, msg, response, logged);
        		continue;
        	}
        	// Carry the reply's own eventId here as well as on a released reply, so that
        	// every received reply reads the same way in Elasticsearch.
        	log.info(Markers2.append("eventType", "SQS_RECEIVE", "direction", "receive",
        			"queueName", queueName, "messageType", RefreshResponse.MESSAGE,
        			"targetHost", response.host(), "targetRegion", response.region(),
        			// Not "eventId": the MDC already carries the running operation's id.
        			// A reply that answers another operation would otherwise be filed
        			// under this one. IGDD-3599.
        			"replyEventId", response.eventId(), "result", response.status()),
        			"SQS receive {} from {}:{}", RefreshResponse.MESSAGE, response.region(), response.host());
            String key = response.region() + ":" + response.host();
            if (usable && results.get(key) != null) {
                results.put(key, response.status());
                // Delete only the messages we processed
                sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
            } else {
				log.warn("Ignoring mismatched response message: {} {}", msg.body(), msg.messageAttributes());
			}
        }
    }

    /**
     * Make a reply that answers another operation available again.
     * <p>
     * Two operations on one instance share one response queue, so a poll can take a
     * reply that the other one waits for. Every receive starts a new visibility period,
     * so the reply must be released on every receive, not only the first. Left alone it
     * stays hidden for the queue's visibility timeout, which is longer than the other
     * operation's wait, and that operation reports a timeout for an instance that
     * answered. See IGDD-3599.
     * </p>
     * <p>
     * One second rather than zero. The reply is still available well inside the other
     * operation's 15 second wait, and a reply whose operation already ended is touched
     * about once a second instead of continuously.
     * </p>
     *
     * @param sqsClient	Client for this instance's own region
     * @param queueUrl	URL of this instance's response queue
     * @param queueName	Name of that queue, for the log record
     * @param msg	The message as received, carrying the current receipt handle
     * @param response	The reply read from that message
     * @param logged	Identifiers of foreign replies already recorded in this wait
     */
    private void releaseForeignReply(SqsClient sqsClient, String queueUrl, String queueName, Message msg,
    		RefreshResponse response, Set<String> logged) {
    	if (logged.add(msg.messageId())) {
    		// Once per wait. One record per poll would bury the replies that do answer
    		// this operation.
        	log.info(Markers2.append("eventType", "SQS_RECEIVE", "direction", "receive",
        			"queueName", queueName, "messageType", RefreshResponse.MESSAGE,
        			"targetHost", response.host(), "targetRegion", response.region(),
        			"replyEventId", response.eventId(), "result", response.status()),
        			"SQS receive {} from {}:{} for another operation", RefreshResponse.MESSAGE,
        			response.region(), response.host());
    	}
    	try {
    		sqsClient.changeMessageVisibility(
    				r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).visibilityTimeout(1));
    	} catch (Exception e) {
    		// Someone else's reply. Failing to release it must not end this wait.
    		log.warn("Could not release reply for operation {}: {}", response.eventId(), e.getMessage());
    	}
    }

    /**
     * Record one summary line for a distributed operation.
     * <p>
     * This line answers "did every region apply the command" without a search across
     * instances.
     * </p>
     *
     * @param eventId	Correlation identifier of the operation
     * @param results	Outcomes keyed {@code region:hostname}
     */
    private static void logOperationComplete(String eventId, Map<String, String> results) {
    	// The fleet key reports whether the instance list could be read. It is not an
    	// instance, so counting it would describe a remote instance that was never
    	// discovered and never contacted. The region summary below still carries it, so
    	// the discovery failure is still in this record. IGDD-3599.
    	long total = results.keySet().stream().filter(k -> !FLEET_KEY.equals(k)).count();
    	long ok = results.entrySet().stream()
    			.filter(e -> !FLEET_KEY.equals(e.getKey()))
    			.filter(e -> isSuccess(e.getValue()))
    			.count();
    	log.info(Markers2.append("eventType", "SQS_OPERATION_COMPLETE",
    			"instances", total, "succeeded", ok, "failed", total - ok,
    			"regions", summarizeByRegion(results)),
    			"Distributed operation complete: {} of {} instances applied the command", ok, total);
    }

    /**
     * Start a listener thread that waits for refresh requests on the refresh queue.
     * When a message is received, makes the appropriate API call on the DBController.
     */
    public void startRefreshListener() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            SqsClient sqsClient = clientFor(region);
            String queueName = getRequestQueueName(myHostName());
            String queueUrl;
            try {
                queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            } catch (Exception e) {
                log.error(Markers2.append(e), "Could not get refresh request queue URL: {}", e.getMessage());
                return;
            }
            log.info("Started refresh listener on queue: {}", queueName);
            refreshLoop(sqsClient, queueUrl);
        });
    }

	private void refreshLoop(SqsClient sqsClient, String queueUrl) {
		while (true) {
		    try {
		        List<Message> messages = sqsClient.receiveMessage(
		        	r -> r.queueUrl(queueUrl)
		        			.messageAttributeNames("All")
		        			.maxNumberOfMessages(10)
		        			.waitTimeSeconds(10)
		        	).messages();
		        for (Message msg : messages) {
		        	if (RefreshRequest.MESSAGE.equals(msg.body())) {
		        		RefreshRequest request = RefreshRequest.fromMessage(msg);
		        		// Put the correlation id in the MDC, the way the request valve does for
		        		// an inbound HTTP request. Every record this thread writes then carries
		        		// it once. A log statement that appends it again produces a duplicate
		        		// JSON key, which is the fault commit a8f40240d fixed for "level".
		        		MDC.put(EventId.EVENTID_KEY, request.eventId());
		        		try {
			        		log.info(Markers2.append("eventType", "SQS_RECEIVE", "direction", "receive",
			        				"queueName", getRequestQueueName(myHostName()),
			        				"messageType", RefreshRequest.MESSAGE,
			        				"keyId", request.jti()),
			        				"SQS receive {} from {}:{}", RefreshRequest.MESSAGE,
			        				request.senderRegion(), request.senderHost());
			        		handleRefreshRequest(request);
			        		sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
		        		} finally {
		        			MDC.remove(EventId.EVENTID_KEY);
		        		}
		        	}
		        }
		    } catch (Exception e) {
		        log.error(Markers2.append(e), "Error processing refresh request: {}", e.getMessage());
		    }
		}
	}

	// Package-private so a test can drive one received command without starting the
	// listener thread. IGDD-3599.
	void handleRefreshRequest(RefreshRequest request) {
		log.info(Markers2.append("refreshRequest", request), "Refresh Request {}", request);
		String status = applyLocally(request);
		RefreshResponse response = new RefreshResponse(myHostName(), region, request.eventId(), status);
		sendRefreshResponse(request, response);
	}

	/**
	 * Apply a command on this instance, in process.
	 * <p>
	 * CAUTION: this method must never call a REST endpoint of this instance. If it
	 * did, and that endpoint distributes commands, every receiving instance would
	 * distribute again and the operation would never terminate. See IGDD-3599,
	 * design decision D4.
	 * </p>
	 * <p>
	 * Authorization is not rechecked here. The operator's request was authorized once,
	 * on the instance that received it, and this message arrived on a queue that only
	 * Hub instances can write to.
	 * </p>
	 *
	 * <p>
	 * Public because both controllers apply commands through it. The reset endpoints
	 * must call it for the instance that serves the request: {@link #distribute} skips
	 * the caller, so without this the serving instance is the one instance that never
	 * resets.
	 * </p>
	 *
	 * @param request The command to apply
	 * @return {@code "OK"} when every requested action succeeded, otherwise a short
	 *         description of the failure
	 */
	public String applyLocally(RefreshRequest request) {
		String result;
		try {
			if (request.jti() != null && apiKeyPrincipalProvider != null) {
				apiKeyPrincipalProvider.evictCredential(request.jti());
			}
			if (request.refreshConfig()) {
				configuration.refresh();
			}
			if (request.reset()) {
				resetBreakers(request.destId());
			}
			result = "OK";
		} catch (RuntimeException e) {
			log.error(Markers2.append(e), "Could not apply {}: {}", request, e.getMessage());
			result = Objects.toString(e.getMessage(), "NOT OK");
		}
		// Without this record the instance that serves an operator's request applies the
		// command silently: the remote instances log SQS_RECEIVE, and the serving one
		// logs nothing. An operator tracing one eventId would see every hop but the
		// first. This record also carries the outcome, which SQS_RECEIVE does not.
		log.info(Markers2.append("eventType", "COMMAND_APPLIED",
				"reset", request.reset(), "refreshConfig", request.refreshConfig(),
				"destId", request.destId(), "keyId", request.jti(), "result", result),
				"Applied command on this instance: reset={} destId={} result={}",
				request.reset(), request.destId(), result);
		return result;
	}

	private void resetBreakers(String destId) {
		if (destId == null) {
			endpointStatusService.resetCircuitBreakers();
			return;
		}
		// A null return means the repository holds no status for the destination. That
		// also happens when its cache is empty and the read behind it failed, so the
		// breaker state is unknown rather than known to be clear. The instance that
		// serves the operator's request answers 404 in that case, so an instance that
		// receives the command must not answer OK. IGDD-3599.
		if (endpointStatusService.resetCircuitBreakerById(destId) == null) {
			throw new IllegalStateException("No status for destination " + destId);
		}
	}

    private void sendRefreshResponse(RefreshRequest request, RefreshResponse response) {
        String responseQueueName = getResponseQueueName(request.senderHost());
        try {
            SqsClient sqsClient = clientFor(request.senderRegion());
            String responseQueueUrl = sqsClient.getQueueUrl(r -> r.queueName(responseQueueName)).queueUrl();
            sqsClient.sendMessage(response.toSendMessageRequest(responseQueueUrl));
            log.info(Markers2.append("eventType", "SQS_SEND", "direction", "send",
            		"queueName", responseQueueName, "messageType", RefreshResponse.MESSAGE,
            		"targetHost", request.senderHost(), "targetRegion", request.senderRegion(),
            		"result", response.status()),
            		"SQS send {} to {}:{}", RefreshResponse.MESSAGE,
            		request.senderRegion(), request.senderHost());
        } catch (Exception e) {
            log.error(Markers2.append(e, "eventType", "SQS_SEND_FAILED", "direction", "send",
            		"queueName", responseQueueName, "messageType", RefreshResponse.MESSAGE),
            		"Failed to send refresh response to {}: {}", responseQueueName, e.getMessage());
        }
    }
 
}