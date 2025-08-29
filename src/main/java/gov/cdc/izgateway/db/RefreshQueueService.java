package gov.cdc.izgateway.db;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import java.util.List;
import java.util.Map;
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
public class RefreshQueueService {
    private static final Logger log = LoggerFactory.getLogger(RefreshQueueService.class);
    private static final String WAITING_FOR_RESPONSE = "Waiting for Response";
    private static final Map<String, SqsClient> sqsClients = new TreeMap<>();
    private static final List<String> createdQueues = new java.util.ArrayList<>();
	private static final String region = Objects.toString(System.getenv("AWS_REGION"), "unknown");
    private final DbController dbController;
    private static MessageAttributeValue newStringAttribute(String value) {
    	return MessageAttributeValue.builder().stringValue(value).dataType("String").build();
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
    public record RefreshRequest(boolean reset, String eventId, String senderHost, String senderRegion) {
    	public static String MESSAGE = "RefreshRequest";
        @Override
        public String toString() {
            return String.format(
                "{\"reset\":%b,\"eventId\":\"%s\",\"senderHost\":\"%s\",\"senderRegion\":\"%s\"}",
                reset, Objects.toString(eventId, ""), Objects.toString(senderHost, ""), Objects.toString(senderRegion, "")
            );
        }
        
        /**
         * Create a RefreshRequest from an SQS message.
         * @param msg	The SQS message
         * @return	 The RefreshRequest
         */
        public static RefreshRequest fromMessage(Message msg) {
			boolean reset = Boolean.parseBoolean(msg.messageAttributes().get("reset").stringValue());
			String eventId = msg.messageAttributes().get("eventId").stringValue();
			String senderHost = msg.messageAttributes().get("senderHost").stringValue();
			String senderRegion = msg.messageAttributes().get("senderRegion").stringValue();
			return new RefreshRequest(reset, eventId, senderHost, senderRegion);
		}
        
        /**
         * @return A map of the attributes of the refresh request for use in SQS message attributes
         */
        public Map<String, MessageAttributeValue> toMap() {
        	Map<String, MessageAttributeValue> map = new TreeMap<>();
			map.put("reset", newStringAttribute(Boolean.toString(reset)));
			map.put("eventId", newStringAttribute(eventId));
			map.put("senderHost", newStringAttribute(senderHost));
			map.put("senderRegion", newStringAttribute(senderRegion));
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
        	String host = msg.messageAttributes().get("host").stringValue();
        	String region = msg.messageAttributes().get("region").stringValue();
        	String eventId = msg.messageAttributes().get("eventId").stringValue();
        	String status = msg.messageAttributes().get("status").stringValue();
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
    
    RefreshQueueService(String region, DbController dbController) {
		this.dbController = dbController;
		createRefreshQueues();
		// Add a shutdown hook to delete the created queues
        Runtime.getRuntime().addShutdownHook(new Thread(RefreshQueueService::deleteQueues, "SQS-Queue-ShutdownHook"));
        // Start the refresh loop to listen for refresh requests
		startRefreshListener();
	}
    
	private static SqsClient getClient(String hostRegion) {
		return sqsClients.computeIfAbsent(hostRegion, r -> SqsClient.builder().region(Region.of(r)).build());
	}

	private static void createRefreshQueues() {
		// Create an SQS queue named after the hostname (serverName) to receive refresh messages
		try {
			SqsClient sqsClient = getClient(region);
			String requestQueueName = getRequestQueueName(SystemUtils.getHostName());
			String responseQueueName = getResponseQueueName(SystemUtils.getHostName());
			createQueue(requestQueueName, sqsClient);
			createQueue(responseQueueName, sqsClient);
		} catch (Exception e) {
			log.error("Failed to create SQS queues: {}", e.getMessage());
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
		    log.error("Error during shutdown hook for SQS queue deletion: {}", e.getMessage());
		}
	}

	private static void deleteQueue(SqsClient sqsClient, String queueName) {
		try {
		    String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
		    sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
		    log.info("Deleted SQS queue '{}' on shutdown", queueName);
		} catch (Exception e) {
		    log.error("Failed to delete SQS queue '{}' on shutdown: {}", queueName, e.getMessage());
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
     * Send a refresh request to the specified host in the specified region.
     * @param request The refresh request to send
     * @param results	A map to record the results of the send operation
     * @param host	The host to send the message to
     * @param hostRegion	The region the host is in
     * @return results map with the status of the send operation
     */
    public Map<String, String> sendRefreshMessage(RefreshRequest request, Map<String, String> results, String host, String hostRegion) {
        SqsClient sqsClient = getClient(hostRegion);
        String endpointName = hostRegion + ":" + host;
        String queueName = getRequestQueueName(host);
        try {
            String queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            sqsClient.sendMessage(request.toSendMessageRequest(queueUrl));
        } catch (Exception e) {
            log.error("Exception sending SQS message to {}: {}", queueName, e.getMessage());
            results.put(endpointName, "Send Failure");
            return results;
        }
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
        SqsClient sqsClient = sqsClients.computeIfAbsent(region, r -> SqsClient.builder().region(Region.of(r)).build());
        String queueName = getResponseQueueName(SystemUtils.getHostName());
        String queueUrl;
        try {
            queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        } catch (Exception e) {
            log.error("Could not get response queue URL: {}", e.getMessage());
            results.replaceAll((k, v) -> v.equals(WAITING_FOR_RESPONSE) ? "Internal Error" : v);
            return results;
        }
        long timeoutMillis = 15000; // 15 seconds
        long start = System.currentTimeMillis();
        boolean allResponded = results.values().stream().noneMatch(v -> v.equals(WAITING_FOR_RESPONSE));
        while (!allResponded && System.currentTimeMillis() - start < timeoutMillis) {
            List<Message> messages = sqsClient.receiveMessage(
            	r -> r.queueUrl(queueUrl)
            			.messageSystemAttributeNames(MessageSystemAttributeName.ALL)
            			.maxNumberOfMessages(10)
            			.waitTimeSeconds(2)
            		).messages();
            for (Message msg : messages) {
            	// TODO: Change to log.debug after testing
            	log.info("Received message: {} {}", msg.body(), msg.messageAttributes());
            	if (!RefreshResponse.MESSAGE.equals(msg.body())) {
            		continue;
            	}
            	RefreshResponse response = RefreshResponse.fromMessage(msg);
                String key = response.region() + ":" + response.host();
                // Only update the result if the eventId matches and it is a response we are waiting for
                if (response.host() != null && response.status() != null && eventId.equals(response.eventId()) && results.get(key) != null) {
                    results.put(key, response.status());
                    // Delete only the messages we processed
                    sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
                } else {
					log.warn("Ignoring mismatched response message: {} {}", msg.body(), msg.messageAttributes());
				}
                
            }
            allResponded = results.values().stream().noneMatch(v -> v.equals(WAITING_FOR_RESPONSE));
        }
        if (!allResponded) {
			results.replaceAll((k, v) -> v.equals(WAITING_FOR_RESPONSE) ? "Timed Out" : v);
		}
        return results;
    }

    /**
     * Start a listener thread that waits for refresh requests on the refresh queue.
     * When a message is received, makes the appropriate API call on the DBController.
     */
    public void startRefreshListener() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            SqsClient sqsClient = sqsClients.computeIfAbsent(region, r -> SqsClient.builder().region(Region.of(r)).build());
            String queueName = getRequestQueueName(SystemUtils.getHostName());
            String queueUrl;
            try {
                queueUrl = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            } catch (Exception e) {
                log.error("Could not get refresh request queue URL: {}", e.getMessage());
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
		        			.messageSystemAttributeNames(MessageSystemAttributeName.ALL)
		        			.maxNumberOfMessages(10)
		        			.waitTimeSeconds(10)
		        	).messages();
		        for (Message msg : messages) {
		        	// TODO: Change to log.debug after testing
		        	log.info("Received message: {} {}", msg.body(), msg.messageAttributes());
		        	if (RefreshRequest.MESSAGE.equals(msg.body())) {
		        		RefreshRequest request = RefreshRequest.fromMessage(msg);
		        		handleRefreshRequest(sqsClient, queueUrl, request);
		        		sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()));
		        	} 
		        }
		    } catch (Exception e) {
		        log.error("Error processing refresh request: {}", e.getMessage());
		    }
		}
	}

	private void handleRefreshRequest(SqsClient sqsClient, String queueUrl, RefreshRequest request) {
		log.info("Received refresh request: {}", request.toString());
		dbController.refresh();
		if (request.reset()) {
		    dbController.resetEndpoint(SystemUtils.getHostName(), request.eventId());
		}
		RefreshResponse response = new RefreshResponse(SystemUtils.getHostName(), region, request.eventId(), "OK");
		sendRefreshResponse(response);
	}

    private void sendRefreshResponse(RefreshResponse response) {
        String responseQueueName = getResponseQueueName(response.host());
        try {
            SqsClient sqsClient = getClient(response.region());
            String responseQueueUrl = sqsClient.getQueueUrl(r -> r.queueName(responseQueueName)).queueUrl();
            sqsClient.sendMessage(response.toSendMessageRequest(responseQueueUrl));
        } catch (Exception e) {
            log.error("Failed to send refresh response to {}: {}", responseQueueName, e.getMessage());
        }
    }
 
}