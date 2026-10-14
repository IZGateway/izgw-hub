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
		// One generated file serves all four roles (server key/trust, client key/trust) --
		// same pattern the real awsdev_keystore.bcfks uses for server key==trust by default;
		// here we also point the client's outbound trust-store (normally a separate
		// izgw_client_trust.bcfks) at it, since this test makes no outbound destination calls.
		registry.add("server.ssl.key-store", () -> path);
		registry.add("server.ssl.key-store-password", () -> password);
		registry.add("server.ssl.trust-store", () -> path);
		registry.add("server.ssl.trust-store-password", () -> password);
		registry.add("client.ssl.key-store", () -> path);
		registry.add("client.ssl.key-store-password", () -> password);
		registry.add("client.ssl.trust-store", () -> path);
		registry.add("client.ssl.trust-store-password", () -> password);
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

		// Force immediate detection rather than waiting out the background scheduler's
		// monitoring period -- this is the behavior under test: reload without a restart.
		int status = callAdminReload();
		assertEquals(HttpURLConnection.HTTP_OK, status, "POST /rest/trust?reload=true should succeed as ADMIN");

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
			Certificate[] serverCerts = conx.getServerCertificates();
			return ((X509Certificate) serverCerts[0]).getSerialNumber();
		} finally {
			conx.disconnect();
		}
	}

	/** Calls the admin reload endpoint and returns the HTTP status code. */
	private int callAdminReload() throws Exception {
		HttpsURLConnection conx = openAdminConnection("POST", true);
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
