package gov.cdc.izgateway.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.repository.IJurisdictionRepository;

@Service
@Lazy(false)
public class JurisdictionService implements IJurisdictionService {
	private static final long MAX_AGE_IN_MINUTES = 60;  // Update every hour
	// Keep track of the singleton to simplify Destination entity class
	private static IJurisdictionService instance;
	private Map<Integer, IJurisdiction> cache = new LinkedHashMap<>();
	private IJurisdictionRepository jurisdictionRepository;
	long lastUpdate = 0;
	
	public JurisdictionService(IJurisdictionRepository jurisdictionRepository) {
		this.jurisdictionRepository = jurisdictionRepository;
		setInstance(this);
	}
	
	private static void setInstance(IJurisdictionService jurisdictionService) {
		instance = jurisdictionService;
	}

	public static IJurisdictionService getInstance() {
		return instance;
	}

	@Override
	public IJurisdiction getJurisdiction(int jurisdictionId) {
		boolean refreshed = false;
		if (cache.isEmpty() || needsUpdate()) {
			refresh();
			refreshed = true;
		}
		IJurisdiction j = cache.get(jurisdictionId);
		if (j == null && !refreshed) {
			// We didn't get a value, but someone thinks it exists, refresh.
			refresh();
			j = cache.get(jurisdictionId);
		}
		return j;
	}
	
	private boolean needsUpdate() {
		long age = System.currentTimeMillis() - lastUpdate;
		return Duration.ofMillis(age).toMinutes() > MAX_AGE_IN_MINUTES;
	}

	@Override
	public void refresh() {
		Map<Integer, IJurisdiction> newCache = new LinkedHashMap<>();
		jurisdictionRepository.findAll().forEach(j -> newCache.put(j.getJurisdictionId(), j));
		lastUpdate = System.currentTimeMillis();
		cache = newCache;
	}
}
