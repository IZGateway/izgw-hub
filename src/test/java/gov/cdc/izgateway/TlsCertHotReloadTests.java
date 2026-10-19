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
import javax.net.ssl.TrustManagerFactory;

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
 * <p>The fix is {@code ServerSslBundleReloader} (izgw-core), scheduled by izgw-hub's
 * {@code ServerSslBundleReloadScheduler}: it polls the server's {@code SslBundle} keystore file's
 * modification time and, on a change, rebuilds the bundle and pushes it via
 * {@code SslBundleRegistry.updateBundle(...)}. This is deliberately <em>not</em> Spring Boot's own
 * {@code reload-on-update} (disabled in {@code application.yml}): that mechanism is backed by
 * {@code java.nio.file.WatchService} (inotify on Linux), which does not detect a change written
 * by a different client to a network filesystem -- and this application's keystore lives on an
 * AWS EFS (NFSv4.1) volume in every real deployment. Polling file mtime is the same mechanism
 * already proven correct on EFS for the outbound client TLS path.
 *
 * <p>This test forces an immediate reload via {@code GET /rest/trust?reload=true} (mTLS, admin
 * client cert) rather than waiting out the scheduler's own poll interval -- that endpoint calls
 * the exact same {@code ServerSslBundleReloader.reloadIfChanged()} method the scheduler does, just
 * synchronously and on demand. Before this fix, this test fails: the certificate served after
 * rotation still matches the pre-rotation serial.
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
	// The forced reload=true trigger is synchronous, so this is defensive insurance against any
	// subtle propagation delay on the very next connection, not the primary detection mechanism.
	private static final Duration AWAIT_RELOAD_TIMEOUT = Duration.ofSeconds(2);

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
		// File.deleteOnExit() on a directory only succeeds if it is empty at shutdown, so the
		// keystore file living inside sslDir needs its own registration too, or the directory
		// (and this file) leak on every run.
		keystoreFile.deleteOnExit();

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
		// Disable the fixed-port (9081) management connector: Application.java only adds it
		// when additionalPort >= 1. WebEnvironment.RANDOM_PORT only randomizes server.port,
		// not this second connector, so with Surefire's reuseForks=true, this test's
		// separately-configured context collides with ApplicationTests' already-bound 9081
		// in the same JVM (BindException: Address already in use). Not needed for this test.
		registry.add("server.local-port", () -> "0");
	}

	@Test
	void certificateRotationIsPickedUpWithoutRestart() throws Exception {
		// Built once and reused throughout: none of this client-side material changes, only the
		// server's presented certificate does.
		SSLSocketFactory clientFactory = clientSslSocketFactory();

		BigInteger baselineServing = capturePresentedCertSerial(clientFactory, false);
		assertEquals(initialCert.getSerialNumber(), baselineServing,
				"Server should initially present the generated baseline certificate");

		// Rotate: atomically replace the live keystore file with the "renewed" one -- a plain
		// local file operation, exactly as design.md Decision 5 describes.
		KeyStore rotated = TlsTestKeystoreFactory.newServerKeyStore(SERVER_ALIAS, rotatedKeyPair, rotatedCert, STORE_PASSWORD);
		TlsTestKeystoreFactory.addTrustedCert(rotated, CLIENT_ALIAS, clientCert);
		File tempFile = new File(sslDir, "awsdev_keystore.bcfks.new");
		TlsTestKeystoreFactory.writeKeyStore(rotated, tempFile, STORE_PASSWORD);
		Files.move(tempFile.toPath(), keystoreFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

		// Force an immediate reload via the same endpoint an operator would use, rather than
		// waiting out ServerSslBundleReloadScheduler's own poll interval -- this call is
		// synchronous, so the live connector is already updated by the time it returns.
		BigInteger forcedServing = capturePresentedCertSerial(clientFactory, true);
		assertEquals(rotatedCert.getSerialNumber(), forcedServing,
				"Server should present the rotated certificate's exact serial immediately after the forced reload");

		// Defensive insurance, not the primary detection mechanism: confirm it still holds on a
		// fresh connection shortly after.
		BigInteger postRotationServing = awaitRotatedCertificate(clientFactory, baselineServing);
		assertNotEquals(baselineServing, postRotationServing,
				"Server is still presenting the pre-rotation certificate -- hot reload did not happen");
		assertEquals(rotatedCert.getSerialNumber(), postRotationServing,
				"Server should now present the rotated certificate's exact serial");
	}

	/**
	 * Polls the server's presented certificate until it differs from {@code baseline} or
	 * {@link #AWAIT_RELOAD_TIMEOUT} elapses, returning whatever the last observed serial was.
	 */
	private BigInteger awaitRotatedCertificate(SSLSocketFactory clientFactory, BigInteger baseline) throws Exception {
		long deadline = System.nanoTime() + AWAIT_RELOAD_TIMEOUT.toNanos();
		BigInteger lastSeen;
		do {
			lastSeen = capturePresentedCertSerial(clientFactory, false);
			if (!lastSeen.equals(baseline)) {
				return lastSeen;
			}
			Thread.sleep(100); // NOSONAR java:S2925 -- short, bounded defensive poll; see javadoc above
		} while (System.nanoTime() < deadline);
		return lastSeen;
	}

	/**
	 * Connects over TLS and returns the serial number of the certificate the server presents.
	 * @param reload if true, calls {@code GET /rest/trust?reload=true} to force an immediate
	 * reload before capturing; if false, captures without forcing anything.
	 */
	private BigInteger capturePresentedCertSerial(SSLSocketFactory clientFactory, boolean reload) throws Exception {
		HttpsURLConnection conx = openAdminConnection(clientFactory, reload);
		try {
			conx.connect();
			Certificate[] serverCerts = conx.getServerCertificates();
			return ((X509Certificate) serverCerts[0]).getSerialNumber();
		} finally {
			conx.disconnect();
		}
	}

	private HttpsURLConnection openAdminConnection(SSLSocketFactory clientFactory, boolean reload) throws Exception {
		URI uri = URI.create("https://localhost:" + port + "/rest/trust?reload=" + reload);
		HttpsURLConnection conx = (HttpsURLConnection) uri.toURL().openConnection();
		conx.setRequestMethod("GET");
		conx.setSSLSocketFactory(clientFactory);
		// Hostname verification is deliberately disabled: the generated test certs use the
		// real server DN (CN=dev.izgateway.org, matching design.md Decision 5) while this test
		// connects via loopback ("localhost") to exercise the embedded listener directly -- the
		// hostname mismatch is expected and intentional, not a validation gap. Which certificate
		// is presented is validated against a pinned trust store (see clientSslSocketFactory()),
		// not left open to "any" certificate. CodeQL's inline `lgtm[rule-id]` suppression comment
		// does not take effect under this repo's CodeQL setup (confirmed: the alert stayed open
		// with the comment in place) -- dismissed via the code-scanning alerts API instead
		// (reason: "used in tests"), not via a comment here.
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
	 * Builds an in-memory (BCFKS, never written to disk) client SSLContext presenting the
	 * generated admin test cert, and trusting exactly the two certificates this run generated
	 * (baseline + rotated) -- pinned, not a blanket trust-all TrustManager, since the point of
	 * this test is to observe which of those two specific certificates the server presents.
	 * Uses BCFKS/BCFIPS and explicitly pins the BCJSSE provider, matching this project's
	 * {@code ClientTlsSupport} and the rest of this diff's key material -- not just FIPS-policy
	 * consistency, but because the real {@code Application} this test boots sets
	 * {@code org.bouncycastle.fips.approved_only=true} process-wide, so an unpinned
	 * {@code getInstance(...)} call could otherwise silently resolve through a FIPS-restricted
	 * provider depending on this JVM's provider registration order.
	 */
	private SSLSocketFactory clientSslSocketFactory() throws Exception {
		KeyStore clientKs = KeyStore.getInstance("BCFKS", TlsTestKeystoreFactory.BCFIPS);
		clientKs.load(null, null);
		clientKs.setKeyEntry("client", clientKeyPair.getPrivate(), STORE_PASSWORD, new Certificate[] { clientCert });
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX", "BCJSSE");
		kmf.init(clientKs, STORE_PASSWORD);

		KeyStore trustKs = KeyStore.getInstance("BCFKS", TlsTestKeystoreFactory.BCFIPS);
		trustKs.load(null, null);
		trustKs.setCertificateEntry("initial", initialCert);
		trustKs.setCertificateEntry("rotated", rotatedCert);
		TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
		tmf.init(trustKs);

		SSLContext sslContext = SSLContext.getInstance("TLSv1.2", "BCJSSE");
		sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
		return sslContext.getSocketFactory();
	}
}
