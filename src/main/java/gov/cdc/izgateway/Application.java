package gov.cdc.izgateway;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.apache.catalina.connector.Connector;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.tomcat.util.net.NioEndpoint;
import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.bouncycastle.crypto.EntropySourceProvider;
import org.bouncycastle.crypto.fips.FipsDRBG;
import org.bouncycastle.crypto.util.BasicEntropySourceProvider;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.SSLImplementation;
import gov.cdc.izgateway.security.ocsp.RevocationChecker;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.MessageHeaderService;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorMultiton;
import gov.cdc.izgateway.soap.net.SoapMessageConverter;
import gov.cdc.izgateway.soap.net.SoapMessageWriter;
import gov.cdc.izgateway.status.StatusCheckScheduler;
import gov.cdc.izgateway.utils.UtilizationService;
import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.configuration.AppProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@OpenAPIDefinition(
        info = @Info(
                title = "IZ Gateway 2.0",
                version = "2.0",
                description = "Operations and maintenantence APIs for IZ Gateway",
                license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(url = "https://support.izgateway.org/plugins/servlet/desk/portal/3", 
                name = "IZ Gateway", 
                email = "izgateway@cdc.gov")
        )
)
@SpringBootApplication
@EntityScan(basePackages={"gov.cdc.izgateway.db.model"})
@EnableJpaRepositories(basePackages={"gov.cdc.izgateway.db.repository"})
public class Application implements WebMvcConfigurer {
	private static final Map<String, byte[]> staticPages = new TreeMap<>();
	private static final String BUILD_FILE = "build.txt";
	private static final String LOGO_FILE = "izgw-logo-16x16.ico";
	static final String BUILD = "build";
	static final String LOGO = "logo";
	private static boolean abortOnNoIIS = true;
	private static String serverMode = AppProperties.PROD_MODE_VALUE; 
	private static String serverName;

	@Value("${spring.application.fix-newlines}")
	private boolean fixNewlines;
	@Value("${spring.application.enable-status-check:false}")
	private boolean statusCheck;
	
    // Heartbeat needs it's own thread in order to not be blocked by other background tasks.
    private static ScheduledExecutorService he = 
    		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Heartbeat-Scheduler"));

	private static boolean heartbeatEnabled = true;  // Set to false during debugging to disable heartbeat logging
	private static SecureRandom secureRandom;

	public static void setAbortOnNoIIS(boolean abort) {
		abortOnNoIIS = abort;
	}
	
	private static AbstractHttp11JsseProtocol<?> protocol;
	public static void reloadSsl() {
		if (protocol != null) {
			protocol.reloadSslHostConfigs();
		}
	}
	
	public static void main(String[] args) {
    	initialize();

        try {
        	checkApplication(SpringApplication.run(Application.class, args));
        } catch (BeanCreationException | ApplicationContextException bce) {
        	Throwable rootCause = ExceptionUtils.getRootCause(bce);
        	log.error(Markers2.append(bce), "Unexpected Bean Creation Exception, Root Cause: {}", rootCause.getMessage());
            HealthService.setHealthy(rootCause);
            System.exit(1);
        } catch (Throwable ex) {  // NOSONAR Catch any error
            log.error(Markers2.append(ex), "Unexpected exception: {}", ex.getMessage());
            HealthService.setHealthy(ex);
            System.exit(1);
        }
        
        updateJul();
        
        loadStaticResource(BUILD, BUILD_FILE);
        loadStaticResource(LOGO, LOGO_FILE);
        HealthService.setBuildName(getBuild());
        HealthService.setServerName(serverName);
        String build = new String(staticPages.get(BUILD), StandardCharsets.UTF_8);
        log.info("Application loaded\n{}", build);
        // FUTURE: Get from a configuration property
        long healthStatusCheckInterval = 60;
        he.scheduleAtFixedRate(Application::heartbeat, healthStatusCheckInterval, healthStatusCheckInterval, TimeUnit.SECONDS); 
        heartbeat();
	}

	private static void updateJul() {
		String[] classes = {
			NioEndpoint.class.getName(),
			NioEndpoint.class.getName() + ".handshake",
			NioEndpoint.class.getName() + ".certificate"
		};
		for (String c: classes) {
			Logger l = (Logger) LoggerFactory.getLogger(c); 
			l.setLevel(Level.DEBUG);
		}
	}

	public static class JulInit {
		private static final String LOGGING_PROPERTIES = 
				"handlers = org.slf4j.bridge.SLF4JBridgeHandler\n"
				+ ".level = INFO\n"
				// Must enable FINE level logging in SLF4J to capture NioEndpoint handshake exceptions
				+ "org.apache.tomcat.util.net.NioEndpoint.level = FINE\n"
				+ "org.apache.tomcat.util.net.NioEndpoint.certificate.level = FINE\n"
				+ "org.apache.tomcat.util.net.NioEndpoint.handshake.level = FINE\n";
		
		public JulInit() {
			try {
				ByteArrayInputStream bis = new ByteArrayInputStream(LOGGING_PROPERTIES.getBytes(StandardCharsets.UTF_8));
				LogManager.getLogManager().readConfiguration(bis);
			} catch (SecurityException e) {
				throw new ServiceConfigurationError("Security Exception while configuring logging", e);
			} catch (IOException e) {
				throw new ServiceConfigurationError("IO Exception while configuring logging", e);
			}
		}
	}
	
	private static void initialize() {
		Thread.currentThread().setName("IZ Gateway");
		// Initialize the Utilization Service
		UtilizationService.getUtilization();
		
		System.setProperty("java.util.logging.config.class", JulInit.class.getName());
		
		// This should no longer be necessary, but it doesn't hurt to leave it here
		// in case JUL logging doesn't install it for some reason.
		if (!SLF4JBridgeHandler.isInstalled()) {
			// Redirect all JUL log records to the SLF4J API
	    	SLF4JBridgeHandler.removeHandlersForRootLogger();
	    	SLF4JBridgeHandler.install();
		}
		
        // This is necessary initialization to use BCFKS module
        CryptoServicesRegistrar.setSecureRandom(getSecureRandom());
        Security.insertProviderAt(new BouncyCastleFipsProvider(), 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);

    	// Ensure FIPS Compliance
        System.setProperty("org.bouncycastle.fips.approved_only", "true");
        // Enable renegotiation to allow servers to request client certificate after hand off from application gateway  
        System.setProperty("org.bouncycastle.jsse.client.acceptRenegotiation", "true");
        // Enable JSSE Server Name Identification (SNI) connection extension in client and server connections
        System.setProperty("jsse.enableSNIExtension", "true");
        
        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            @Override
            public void run()
            {
                shutdown();
            }
        });

        // Set the eventId for startup log records to 0
        MDC.put(EventId.EVENTID_KEY, EventId.DEFAULT_TX_ID);
        MDC.put("sessionId", "0");
	}
	
    public static void shutdown() {
    	HealthService.setHealthy(false, "Service Stopped");
    }
    
    static void heartbeat() {
    	if (heartbeatEnabled ) {
	        MDC.put(EventId.EVENTID_KEY, EventId.DEFAULT_TX_ID);
	        log.info(Markers2.append("health", HealthService.getHealth(), "utilization", UtilizationService.getUtilization()), "Heartbeat");
	    }
    }
    
	private static void checkApplication(ConfigurableApplicationContext ctx) {
        IDestinationService destinationService = ctx.getBean(IDestinationService.class);
        serverName = destinationService.getServerName();
        serverMode = ctx.getBean(AppProperties.class).getServerMode();
        IMessageHeaderService messageHeaderService = ctx.getBean(MessageHeaderService.class);
        DataSourceProperties ds = ctx.getBean(DataSourceProperties.class);
        StatusCheckScheduler sc = ctx.getBean(StatusCheckScheduler.class);
        Application app = ctx.getBean(Application.class);
    	new Thread(() -> {
    		// Load Mock IIS in background
    		PerformanceSimulatorMultiton.getInstance(PerformanceSimulatorMultiton.PERFORMANCE_PROFILE_MOCK_IIS);
    	}).start();
        try {
            // Test for database connectivity and prefetch caches.
            List<IDestination> list = destinationService.getAllDestinations();

            if (list.isEmpty() && abortOnNoIIS) {
            	HealthService.setHealthy(false, "No IIS Connections available");
                log.error("No IIS Connections are available from {}", ds.getUrl());
                throw new ServiceConfigurationError("No IIS Connections are available from " + ds.getUrl());
            } else {
                // Prefetch to populate cache
                messageHeaderService.getAllMessageHeaders();
                if (app.statusCheck) {
                	sc.start();
                }
                HealthService.setHealthy(true, "Normal application startup");
                log.info("Connected to {}", ds.getUrl());
            }
        } catch (RuntimeException hex) { // NOSONAR This is handling the exception correctly
        	HealthService.setHealthy(hex);
            log.error(Markers2.append(hex), "Cannot get a database connection to {}: {}", ds.getUrl(), hex.getMessage(), hex);
            throw new ServiceConfigurationError("Cannot get a database connection to " + ds.getUrl(), hex);
        }
	}


	public static String getBuild() {
		byte[] v = staticPages.get(BUILD);
		String version = v == null ? "" : new String(v);
		return StringUtils.substringBetween(version, "Build:", "\n").trim();
	}
	
	public static String getPage(String page) {
		return new String(staticPages.get(page), StandardCharsets.UTF_8);
	}

    /**
     * Generate a a NIST SP 800-90A compliant secure random number
     * generator.
     *
     * @return A compliant generator.
     */
	@Bean
    public static SecureRandom getSecureRandom() {
		/*
         * According to NIST Special Publication 800-90A, a Nonce is
         * A time-varying value that has at most a negligible chance of
         * repeating, e.g., a random value that is generated anew for each
         * use, a timestamp, a sequence number, or some combination of
         * these.
         *
         * The nonce is combined with the entropy input to create the initial
         * DRBG seed.
         */
    	if (secureRandom != null) {
    		return secureRandom;
    	}
        byte[] nonce = ByteBuffer.allocate(8).putLong(System.nanoTime()).array();
        EntropySourceProvider entSource = new BasicEntropySourceProvider(new SecureRandom(), true);
        FipsDRBG.Builder drgbBldr = FipsDRBG.SHA512
                .fromEntropySource(entSource).setSecurityStrength(256)
                .setEntropyBitsRequired(256);
        secureRandom = drgbBldr.build(nonce, true);
        return secureRandom;
    }
    
	private static byte[] loadStaticResource(String name, String location) {
		try (InputStream inStream = Application.class.getClassLoader().getResourceAsStream(location)) {
			byte[] data = IOUtils.toByteArray(inStream);
			staticPages.put(name, data);
			return data;
		} catch (IOException | NullPointerException e) {
			log.error("Cannot load resource '{}' from {}", name, location);
			return new byte[0];
		}
	}

	@Value("${security.enable-csrf:false}")
	private boolean enableCsrf;
	@Value("${server.local-port:9081}") 
	private int additionalPort;
	
	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource")
	@Profile("!test")
	public DataSourceProperties dataSourceProperties() {
		return new DataSourceProperties();
	}


	@Bean
	@ConfigurationProperties("spring.datasource.configuration")
	public HikariDataSource dataSource(DataSourceProperties properties) {
		try {
			log.info("Initializing Data Source: {}", properties.getUrl());
			return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		} finally {
			log.info("Database initialized");
		}
	}
	
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> messageConverters) {
    	SoapMessageConverter smc = new SoapMessageConverter(SoapMessageConverter.INBOUND); 
    	smc.setHub(true);
        messageConverters.add(smc);
        // Sets up SoapMessageWriter to handle \r as &#xD; if true, otherwise 
        // \r in hl7Message will be replaced with \n due to XML Parsing rules.
        SoapMessageWriter.setFixNewLines(fixNewlines);
    }

	public static String getServerMode() {
		return serverMode;
	}
	
	public static boolean isProduction() {
		return !"dev".equalsIgnoreCase(serverMode);
	}
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/**").permitAll())
        	.x509(Customizer.withDefaults())
        	.userDetailsService(userDetailsService());
        if (!enableCsrf) {
        	http.csrf(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }
    
    @Bean 
    public UserDetailsService userDetailsService() {
    	return new UserDetailsService() {
			@Override
			public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    	    	return new User(username, "", AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_USER"));
			}
    	};
    }
    
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer )
    {
      configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON, MediaType.ALL);
    }
    
    @Bean
    TomcatConnectorCustomizer reloadConnectorCustomizer(@Autowired RevocationChecker checker) {
    	return Application::customizeConnector;
    }
    
    public static void customizeConnector(Connector connector) {
    	if ("https".equals(connector.getScheme())) {
	    	ProtocolHandler p = connector.getProtocolHandler();
	    	if (p instanceof AbstractHttp11JsseProtocol<?> jsse) {
	    		Application.protocol = jsse;
	    		jsse.setSslImplementationName(SSLImplementation.class.getName());
	    	}
    	}
    }

    /**
     * Enable local server management port.
     * @param additionalPort	The port to add.
     * @return	The TomcatServletWebServerFactory to use
     */
	@Bean
	TomcatServletWebServerFactory tomcatServletWebServerFactory(
			ObjectProvider<TomcatConnectorCustomizer> connectorCustomizers,
			ObjectProvider<TomcatContextCustomizer> contextCustomizers,
			ObjectProvider<TomcatProtocolHandlerCustomizer<?>> protocolHandlerCustomizers) {
		TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory() {
			@Override
			public WebServer getWebServer(ServletContextInitializer... initializers) {
				log.info("Initializing Tomcat");
				try {
					return super.getWebServer(initializers);
				} finally {
					log.info("Tomcat initialized");
				}
			}
		};
		factory.getTomcatConnectorCustomizers().addAll(connectorCustomizers.orderedStream().toList());
		factory.getTomcatContextCustomizers().addAll(contextCustomizers.orderedStream().toList());
		factory.getTomcatProtocolHandlerCustomizers().addAll(protocolHandlerCustomizers.orderedStream().toList());
        if (additionalPort < 1) {
        	return factory;
        }
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("http");
        connector.setPort(additionalPort);
        connector.setProperty("minSpareThreads", "3");  // This is for local administration, we don't need many.
        factory.addAdditionalTomcatConnectors(connector);
        return factory;
	}
}
