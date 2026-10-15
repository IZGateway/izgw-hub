package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IGDD-3582 regression test: proves the Hub's embedded HTTPS listener picks up a rotated
 * TLS certificate without a process restart.
 *
 * <p>Before the fix in izgw-core ({@code ClientTlsSupport.SslReloader.setProtocol(...)} never
 * called), this test fails: the certificate served after rotation + {@code /rest/trust?reload=true}
 * still matches the pre-rotation serial. After the fix, it passes.
 *
 * <p>All key material is generated fresh by {@link TlsTestKeystoreFactory} for this run only --
 * nothing is checked into the repository, so there is nothing here that can expire.
 *
 * <p>Requires the same AWS DynamoDB / Elastic access {@link ApplicationTests} already requires
 * to boot the real application context -- this is not a new environmental requirement introduced
 * by this test.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ComponentScan("gov.cdc.izgateway")
class TlsCertHotReloadTests {
	private static final char[] STORE_PASSWORD = "password".toCharArray();
	private static final String SERVER_ALIAS = "dev.izgateway.org";
	private static final String CLIENT_ALIAS = "admin-client";

	// NOT a JUnit @TempDir: that field is injected by a JUnit extension that runs at normal
	// test-instance setup time, but @DynamicPropertySource runs earlier, during Spring's
	// context-preparation phase -- an @TempDir field would still be null when this class
	// needs it (confirmed: without this, the keystore landed in the project's working
	// directory instead of a temp dir, because `new File(null, name)` falls back to a
	// relative path). Created and deleted manually instead, so it's available immediately.
	static File sslDir;
	static File keystoreFile;
	static KeyPair initialKeyPair;
	static X509Certificate initialCert;
	static KeyPair rotatedKeyPair;
	static X509Certificate rotatedCert;
	static KeyPair clientKeyPair;
	static X509Certificate clientCert;

	static {
		Application.setAbortOnNoIIS(false);
		Application.skipMigrations(true);
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	ApplicationContext context;

	@DynamicPropertySource
	static void registerSslProperties(DynamicPropertyRegistry registry) throws Exception {
		sslDir = Files.createTempDirectory("tls-hot-reload-test-").toFile();
		sslDir.deleteOnExit();
		keystoreFile = new File(sslDir, "awsdev_keystore.bcfks");

		clientKeyPair = TlsTestKeystoreFactory.generateKeyPair();
		clientCert = TlsTestKeystoreFactory.selfSign(clientKeyPair, TlsTestKeystoreFactory.clientDn(), 30);

		initialKeyPair = TlsTestKeystoreFactory.generateKeyPair();
		initialCert = TlsTestKeystoreFactory.selfSign(initialKeyPair, TlsTestKeystoreFactory.serverDn(), 30);
		rotatedKeyPair = TlsTestKeystoreFactory.generateKeyPair();
		rotatedCert = TlsTestKeystoreFactory.selfSign(rotatedKeyPair, TlsTestKeystoreFactory.serverDn(), 30);

		KeyStore initial = TlsTestKeystoreFactory.newServerKeyStore(SERVER_ALIAS, initialKeyPair, initialCert, STORE_PASSWORD);
		TlsTestKeystoreFactory.addTrustedCert(initial, CLIENT_ALIAS, clientCert);
		TlsTestKeystoreFactory.writeKeyStore(initial, keystoreFile, STORE_PASSWORD);

		String path = keystoreFile.getAbsolutePath();
		String password = new String(STORE_PASSWORD);
		// Override the SERVER's own key/trust store -- that's the embedded Tomcat listener
		// identity this test actually exercises, and server.ssl.trust-store is what decides
		// whether the server accepts our generated admin client cert during the mTLS
		// handshake.
		registry.add("server.ssl.key-store", () -> path);
		registry.add("server.ssl.key-store-password", () -> password);
		registry.add("server.ssl.trust-store", () -> path);
		registry.add("server.ssl.trust-store-password", () -> password);
		// Also override client.ssl.key-store (but deliberately NOT client.ssl.trust-store --
		// see below) to the same rotating file. ClientTlsSupport.updateTrust(), which
		// GET /rest/trust?reload=true triggers, gates entirely on
		// checkForUpdates() == clientKeyStore.isOutOfDate() || clientTrustStore.isOutOfDate().
		// It never looks at server.ssl.* at all. Rotating only the server file (as an earlier
		// version of this test did) is therefore invisible to updateTrust() -- it never fires
		// any trust-changed listener, so SslReloader.reloadSsl() is never called regardless of
		// whether the fix itself works. Pointing client.ssl.key-store at the same file makes
		// the rotation detectable via clientKeyStore.isOutOfDate().
		// client.ssl.trust-store governs the application's own *outbound* calls to other
		// services (e.g. ElasticStatusRepository's background status check) and must keep
		// using its real, CI-provided configuration -- overriding it broke that unrelated
		// subsystem in an earlier version of this test (Elastic Cloud's cert wasn't trusted by
		// our narrow test keystore, which the app treated as fatal and shut down on).
		// client.ssl.key-store is only the client's own outbound identity (not used to
		// validate remote servers like Elastic Cloud), so overriding just this one is safe.
		registry.add("client.ssl.key-store", () -> path);
		registry.add("client.ssl.key-store-password", () -> password);
		// Explicitly set the key alias. Without it, CI logs showed the *initial* load working
		// (apparently falling back to "the only/first entry" when no alias is configured) but
		// reloadSslHostConfigs() specifically looking for alias "tomcat" (Tomcat's own default)
		// and finding nothing -- "Certificate details not available as the certificate chain
		// returned from the SSLContext was empty". That left the reloaded SSLContext broken/
		// empty, so the original SSLContext kept serving -- which is exactly what made this
		// look like a hot-reload failure when every other part of the fix was already correct.
		registry.add("server.ssl.key-alias", () -> SERVER_ALIAS);
		// Disable the fixed-port (9081) management connector: Application.java only adds it
		// when additionalPort >= 1. WebEnvironment.RANDOM_PORT only randomizes server.port,
		// not this second connector, so with Surefire's reuseForks=true, this test's
		// separately-configured context collides with ApplicationTests' already-bound 9081
		// in the same JVM (BindException: Address already in use). Not needed for this test.
		registry.add("server.local-port", () -> "0");
	}

	@Test
	void certificateRotationIsPickedUpWithoutRestart() throws Exception {
		// TEMPORARY diagnostic (IGDD-3582): confirm sslReloadConnectorCustomizer is actually
		// registered as a Spring bean and picked up by name -- ClientTlsSupportTests only
		// proves the method's own logic in isolation, not that Spring wires this @Bean (on a
		// @Component, not a @Configuration class) into the real TomcatServletWebServerFactory.
		log.info("DIAGNOSTIC: registered TomcatConnectorCustomizer beans = {}",
				java.util.Arrays.toString(context.getBeanNamesForType(TomcatConnectorCustomizer.class)));

		BigInteger baselineServing = capturePresentedCertSerial();
		assertEquals(initialCert.getSerialNumber(), baselineServing,
				"Server should initially present the generated baseline certificate");

		// Rotate: atomically replace the live keystore file with the "renewed" one -- a plain
		// local file operation, exactly as design.md Decision 5 describes.
		KeyStore rotated = TlsTestKeystoreFactory.newServerKeyStore(SERVER_ALIAS, rotatedKeyPair, rotatedCert, STORE_PASSWORD);
		TlsTestKeystoreFactory.addTrustedCert(rotated, CLIENT_ALIAS, clientCert);
		File tempFile = new File(sslDir, "awsdev_keystore.bcfks.new");
		TlsTestKeystoreFactory.writeKeyStore(rotated, tempFile, STORE_PASSWORD);
		Files.move(tempFile.toPath(), keystoreFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

		// Force immediate detection rather than waiting out the background scheduler's
		// monitoring period -- this is the behavior under test: reload without a restart.
		int status = callAdminReload();
		assertEquals(HttpURLConnection.HTTP_OK, status, "GET /rest/trust?reload=true should succeed as ADMIN");

		BigInteger postRotationServing = capturePresentedCertSerial();
		assertNotEquals(baselineServing, postRotationServing,
				"Server is still presenting the pre-rotation certificate -- hot reload did not happen");
		assertEquals(rotatedCert.getSerialNumber(), postRotationServing,
				"Server should now present the rotated certificate's exact serial");
	}

	/** Connects over TLS and returns the serial number of the certificate the server presents. */
	private BigInteger capturePresentedCertSerial() throws Exception {
		HttpsURLConnection conx = openAdminConnection("GET", false);
		try {
			conx.connect();
			// TEMPORARY diagnostic (IGDD-3582): log the negotiated session's ID and cipher to
			// rule out (or confirm) TLS session resumption -- if two calls report the same
			// session ID, the handshake was resumed rather than repeated, which would explain
			// seeing the same certificate even if the server has genuinely already reloaded.
			SSLSession session = conx.getSSLSession().orElseThrow();
			log.info("DIAGNOSTIC: sessionId={}, cipherSuite={}, isValid={}",
					bytesToHex(session.getId()), conx.getCipherSuite(), session.isValid());
			Certificate[] serverCerts = conx.getServerCertificates();
			return ((X509Certificate) serverCerts[0]).getSerialNumber();
		} finally {
			conx.disconnect();
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/** Calls the admin reload endpoint and returns the HTTP status code. */
	private int callAdminReload() throws Exception {
		// TrustController.getTrust() is @GetMapping, not @PostMapping -- reload=true is just
		// a query param on the same GET endpoint.
		HttpsURLConnection conx = openAdminConnection("GET", true);
		try {
			conx.connect();
			return conx.getResponseCode();
		} finally {
			conx.disconnect();
		}
	}

	private HttpsURLConnection openAdminConnection(String method, boolean reload) throws Exception {
		URI uri = URI.create("https://localhost:" + port + "/rest/trust?reload=" + reload);
		HttpsURLConnection conx = (HttpsURLConnection) uri.toURL().openConnection();
		conx.setRequestMethod(method);
		conx.setSSLSocketFactory(clientSslSocketFactory());
		conx.setHostnameVerifier((hostname, session) -> true);
		// Disabling keep-alive is necessary but not sufficient on its own: Java's
		// HttpURLConnection connection pool can reuse an already-established TLS connection
		// (and its already-negotiated certificate) for a subsequent request to the same
		// host:port even with a brand-new SSLSocketFactory -- pooling happens at the socket
		// level, below where the factory comes into play. Confirmed every other link in the
		// fix's chain works correctly (diagnostic logging showed customizeConnector running on
		// the real connector, SslReloader.setProtocol/reloadSsl both firing with a non-null,
		// matching protocol instance) before finding this.
		conx.setRequestProperty("Connection", "close");
		return conx;
	}

	/**
	 * Builds an in-memory (PKCS12, never written to disk) client SSLContext presenting the
	 * generated admin test cert, and trusting any server certificate -- validating which
	 * certificate the server presents is this test's job, not the test client's.
	 */
	private SSLSocketFactory clientSslSocketFactory() throws Exception {
		KeyStore clientKs = KeyStore.getInstance("PKCS12");
		clientKs.load(null, null);
		clientKs.setKeyEntry("client", clientKeyPair.getPrivate(), STORE_PASSWORD, new Certificate[] { clientCert });
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(clientKs, STORE_PASSWORD);

		TrustManager trustAll = new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};

		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(kmf.getKeyManagers(), new TrustManager[] { trustAll }, new SecureRandom());
		return sslContext.getSocketFactory();
	}
}
