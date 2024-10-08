package gov.cdc.izgateway.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.db.model.Destination;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.repository.IDestinationRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Destination Service provides access to the Destination Repository.
 * 
 * This is a rather thin wrapper around the JPA repository.
 * 
 * @author Audacious Inquiry
 *
 */
@Slf4j
@Service
public class DestinationService implements InitializingBean, IDestinationService {
    private final IDestinationRepository destinationRepository;
    private Map<String, IDestination> cache = Collections.emptyMap();
    private List<IDestination> list = Collections.emptyList();
    @Value("${data.cache.timeToLive:300}")
    private int refreshPeriod;

    /** The server name as far as the public is concerned */
    @Getter
    @Value("${server.hostname:dev.izgateway.org}")
    private String serverName;
    
    /**
     * The actual port the host is deployed to.
     */
    @Getter
    @Value("${server.port:443}")
    private int serverPort;

    /** The load balancer port (the actual port used by the server as far as
     * the public is concerned.
     */
    @Getter
    @Value("${server.lbPort:443}")
    private int lbPort;

    /** The server protocol used internally */
    @Getter
    @Value("${server.protocol:https}")
    private String serverProtocol;

    /**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() { 
        log.debug("Refresh Scheduled for Destination");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::refresh, 0, refreshPeriod, TimeUnit.SECONDS);
    }
    
    @Override
	public void refresh() {
        list = Collections.unmodifiableList(destinationRepository.findAllByDestTypeId(SystemUtils.getDestType()));
        Map<String, IDestination> map = new LinkedHashMap<>();
        // Initialize new cache
        for (IDestination dest: list) {
        	if (dest.getDestTypeId() != SystemUtils.getDestType()) {
        		continue;  // Ignore destination types we don't care about.
        	}
            if (dest.getDestId() != null) {
                map.put(dest.getDestId(), dest);
            }
        }
        cache = map;
        log.debug("Destinations Refreshed");

    }
    
    /**
     * Construct a service using the specified repository
     * @param destinationRepository the repository
     */
    public DestinationService(IDestinationRepository destinationRepository) {
        this.destinationRepository = destinationRepository;
    }
    
    @Override
	public List<IDestination> getAllDestinations() {
        if (cache.isEmpty()) {
            refresh();
        }
        return list;
    }

    @Override
	public IDestination findByDestId(String destId) {
        if (cache.isEmpty()) {
            refresh();
        }
        return cache.get(destId.toLowerCase());
    }

	@Override
	public void saveAndFlush(IDestination dest) {
		try {
			destinationRepository.saveAndFlush(dest);
			// Update the cache
			cache.put(dest.getDestId(), dest);
		} catch (Exception ex) {
			log.error(Markers2.append(ex), "Unexpected {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
			// Force a reread
			refresh();
			throw ex;
		}
	}
	
	private static String resolvedUrl(String uri, String protocol, String hostname, int port) {
		if (uri == null || !uri.startsWith("/")) {
			return uri;
		}
		return String.format("%s://%s:%d%s", protocol, hostname, port, uri);
	}
	
	@Override
	public String publicUrl(String uri) {
		return resolvedUrl(uri, "https", serverName, lbPort);
	}

	@Override
	public String localUrl(String uri) {
		return resolvedUrl(uri, serverProtocol, "localhost", serverPort);
	}

	@Override
	public String serverOf(String uri) {
		if (uri == null) {
			return null;
		}
		if (uri.startsWith("/")) {
			return serverName;
		}
		// It should be protocol://hostname[:port]?[/path]*[/file]? at this stage. 
		uri = StringUtils.substringAfter(uri, "://");
		if (StringUtils.isEmpty(uri)) {
			return "";  // No hostname part.
		}
		// It should be hostname[:port][/path]*[/file]? at this stage, there may be slashes
		uri = StringUtils.substringBefore(uri, "/");  // remove any terminal path
		// It should be hostname[:port] at this stage, there may be slashes
		uri = StringUtils.substringBefore(uri, ":");
		// Now it should be just hostname
		return uri;
	}

	@Override
	public void clearMaintenance(IDestination dest) {
		dest.setMaintStart(null); 
		dest.setMaintEnd(null);
		dest.setMaintReason(null);
		saveAndFlush(dest);
	}

	@Override
	public IDestination getExample(String destinationId) {
		return Destination.getExample(destinationId);
	}
}
