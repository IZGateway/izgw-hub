package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IGDD-3582 regression test: proves the Hub's embedded HTTPS listener picks up a rotated
 * TLS certificate without a process restart.
 *
 * <p>The fix relies entirely on Spring Boot's own {@code SslBundle} machinery: the server's
 * keystore/truststore are configured as a named bundle ({@code spring.ssl.bundle.jks.izgw-server})
 * with {@code reload-on-update: true}, so Spring's {@code FileWatcher} detects the rotated file
 * and pushes a rebuilt bundle into the live Tomcat connector automatically -- no custom reload
 * code is involved. Before this fix (legacy {@code server.ssl.key-store} properties, which never
 * get any update-handler wiring from Spring Boot), this test fails: the certificate served after
 * rotation still matches the pre-rotation serial, even after waiting out the watch quiet period.
 *
 * <p>All key material is generated fresh by {@link TlsTestKeystoreFactory} for this run only --
 * nothing is checked into the repository, so there is nothing here that can expire.
 *
 * <p>Requires the same AWS DynamoDB / Elastic access {@link ApplicationTests} already requires
 * to boot the real application context -- this is not a new environmental requirement introduced
 * by this test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ComponentScan("gov.cdc.izgateway")
class TlsCertHotReloadTests {
	private static final char[] STORE_PASSWORD = "password".toCharArray();
	private static final String SERVER_ALIAS = "dev.izgateway.org";
	private static final String CLIENT_ALIAS = "admin-client";
	// Short enough to keep the test fast; long enough to comfortably exceed the debounce window
	// Spring's FileWatcher applies after it sees the ENTRY_MODIFY/ENTRY_CREATE event.
	private static final Duration WATCH_QUIET_PERIOD = Duration.ofMillis(200);
	private static final Duration AWAIT_RELOAD_TIMEOUT = Duration.ofSeconds(10);

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
		// Point the izgw-server SslBundle (type/provider already set to bcfks/BCFIPS in
		// application.yml) at this test's generated, ephemeral keystore file. This is the
		// SERVER's own identity AND the truststore deciding whether the generated admin client
		// cert is accepted during the mTLS handshake -- both live in the same bcfks file here.
		registry.add("spring.ssl.bundle.jks.izgw-server.keystore.location", () -> "file:" + path);
		registry.add("spring.ssl.bundle.jks.izgw-server.keystore.password", () -> password);
		registry.add("spring.ssl.bundle.jks.izgw-server.truststore.location", () -> "file:" + path);
		registry.add("spring.ssl.bundle.jks.izgw-server.truststore.password", () -> password);
		registry.add("spring.ssl.bundle.jks.izgw-server.key.alias", () -> SERVER_ALIAS);
		// Shorten Spring's FileWatcher debounce window (default 10s) so the test doesn't have to
		// wait that long for the rotated file to be picked up.
		registry.add("spring.ssl.bundle.watch.file.quiet-period", WATCH_QUIET_PERIOD::toString);
		// Disable the fixed-port (9081) management connector: Application.java only adds it
		// when additionalPort >= 1. WebEnvironment.RANDOM_PORT only randomizes server.port,
		// not this second connector, so with Surefire's reuseForks=true, this test's
		// separately-configured context collides with ApplicationTests' already-bound 9081
		// in the same JVM (BindException: Address already in use). Not needed for this test.
		registry.add("server.local-port", () -> "0");
	}

	@Test
	void certificateRotationIsPickedUpWithoutRestart() throws Exception {
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

		// No manual trigger exists anymore (or is needed): Spring's own FileWatcher picks up the
		// ENTRY_MODIFY event asynchronously, waits out its debounce window, then rebuilds and
		// pushes the bundle into the live connector on its own. Poll until that has happened.
		BigInteger postRotationServing = awaitRotatedCertificate(baselineServing);
		assertNotEquals(baselineServing, postRotationServing,
				"Server is still presenting the pre-rotation certificate -- hot reload did not happen");
		assertEquals(rotatedCert.getSerialNumber(), postRotationServing,
				"Server should now present the rotated certificate's exact serial");
	}

	/**
	 * Polls the server's presented certificate until it differs from {@code baseline} or
	 * {@link #AWAIT_RELOAD_TIMEOUT} elapses, returning whatever the last observed serial was.
	 */
	private BigInteger awaitRotatedCertificate(BigInteger baseline) throws Exception {
		long deadline = System.nanoTime() + AWAIT_RELOAD_TIMEOUT.toNanos();
		BigInteger lastSeen;
		do {
			lastSeen = capturePresentedCertSerial();
			if (!lastSeen.equals(baseline)) {
				return lastSeen;
			}
			Thread.sleep(100);
		} while (System.nanoTime() < deadline);
		return lastSeen;
	}

	/** Connects over TLS and returns the serial number of the certificate the server presents. */
	private BigInteger capturePresentedCertSerial() throws Exception {
		HttpsURLConnection conx = openAdminConnection();
		try {
			conx.connect();
			Certificate[] serverCerts = conx.getServerCertificates();
			return ((X509Certificate) serverCerts[0]).getSerialNumber();
		} finally {
			conx.disconnect();
		}
	}

	private HttpsURLConnection openAdminConnection() throws Exception {
		URI uri = URI.create("https://localhost:" + port + "/rest/trust");
		HttpsURLConnection conx = (HttpsURLConnection) uri.toURL().openConnection();
		conx.setRequestMethod("GET");
		conx.setSSLSocketFactory(clientSslSocketFactory());
		conx.setHostnameVerifier((hostname, session) -> true);
		// Disabling keep-alive is necessary but not sufficient on its own: Java's
		// HttpURLConnection connection pool can reuse an already-established TLS connection
		// (and its already-negotiated certificate) for a subsequent request to the same
		// host:port even with a brand-new SSLSocketFactory -- pooling happens at the socket
		// level, below where the factory comes into play.
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
