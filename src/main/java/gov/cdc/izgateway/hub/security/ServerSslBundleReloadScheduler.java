package gov.cdc.izgateway.hub.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.cdc.izgateway.security.ServerSslBundleReloader;

import lombok.extern.slf4j.Slf4j;

/**
 * Periodically checks whether the embedded HTTPS listener's SSL bundle needs reloading from disk
 * (IGDD-3582). The check-and-reload logic lives in izgw-core's {@link ServerSslBundleReloader};
 * this class only owns the scheduling policy -- how often (or whether) to poll is a deployment
 * decision for this application, not something the shared library should dictate to every
 * consumer. Spring's scheduling infrastructure is enabled globally by {@code @EnableScheduling}
 * on the application class.
 */
@Component
@Slf4j
public class ServerSslBundleReloadScheduler {

	private final ServerSslBundleReloader reloader;

	/**
	 * Create the scheduler. Spring supplies the parameter.
	 * @param reloader	The reloader that checks and reloads the SSL bundle
	 */
	@Autowired
	public ServerSslBundleReloadScheduler(ServerSslBundleReloader reloader) {
		this.reloader = reloader;
	}

	/**
	 * Check if the SSL bundle changed on disk and reload it if necessary. This method logs a
	 * failure and does not throw it.
	 */
	@Scheduled(
			fixedDelayString = "${server.ssl.bundle-reload.interval:PT10S}",
			initialDelayString = "${server.ssl.bundle-reload.initial-delay:PT10S}")
	public void scheduledReload() {
		try {
			reloader.reloadIfChanged();
		} catch (Exception e) { // NOSONAR -- a scheduled job must never let a failure escape its thread
			log.error("Error checking for SSL bundle reload", e);
		}
	}
}
