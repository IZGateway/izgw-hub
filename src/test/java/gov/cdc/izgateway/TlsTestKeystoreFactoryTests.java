package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Standalone test of {@link TlsTestKeystoreFactory} -- no Spring context, no AWS/Elastic
 * dependency. Verifies the ephemeral keystore generation this IGDD-3582 verification relies on
 * actually works before building the full {@code @SpringBootTest} on top of it.
 */
class TlsTestKeystoreFactoryTests {

	@Test
	void generatedServerKeyStoreRoundTripsAndCanBeRotated(@TempDir File dir) throws Exception {
		char[] password = "password".toCharArray();

		KeyPair pair1 = TlsTestKeystoreFactory.generateKeyPair();
		X509Certificate cert1 = TlsTestKeystoreFactory.selfSign(pair1, TlsTestKeystoreFactory.serverDn(), 30);
		KeyStore ks1 = TlsTestKeystoreFactory.newServerKeyStore("dev.izgateway.org", pair1, cert1, password);

		KeyPair pair2 = TlsTestKeystoreFactory.generateKeyPair();
		X509Certificate cert2 = TlsTestKeystoreFactory.selfSign(pair2, TlsTestKeystoreFactory.serverDn(), 30);
		KeyStore ks2 = TlsTestKeystoreFactory.newServerKeyStore("dev.izgateway.org", pair2, cert2, password);

		// Same DN, different serials -- this is the "rotation" the hot-reload test depends on.
		assertEquals(cert1.getSubjectX500Principal(), cert2.getSubjectX500Principal());
		assertNotEquals(cert1.getSerialNumber(), cert2.getSerialNumber());

		KeyPair clientPair = TlsTestKeystoreFactory.generateKeyPair();
		X509Certificate clientCert = TlsTestKeystoreFactory.selfSign(clientPair, TlsTestKeystoreFactory.clientDn(), 30);
		assertTrue(clientCert.getSubjectX500Principal().getName().contains("tls-hot-reload-test.testing.izgateway.org"));

		TlsTestKeystoreFactory.addTrustedCert(ks1, "admin-client", clientCert);
		TlsTestKeystoreFactory.addTrustedCert(ks2, "admin-client", clientCert);

		File file1 = new File(dir, "awsdev_keystore.bcfks");
		File file2 = new File(dir, "awsdev_keystore_rotated.bcfks");
		TlsTestKeystoreFactory.writeKeyStore(ks1, file1, password);
		TlsTestKeystoreFactory.writeKeyStore(ks2, file2, password);

		// Round-trip: read each file back exactly as the application would.
		KeyStore reloaded1 = KeyStore.getInstance("BCFKS", TlsTestKeystoreFactory.BCFIPS);
		try (var in = new java.io.FileInputStream(file1)) {
			reloaded1.load(in, password);
		}
		assertEquals(2, java.util.Collections.list(reloaded1.aliases()).size());
		X509Certificate reloadedServerCert = (X509Certificate) reloaded1.getCertificate("dev.izgateway.org");
		assertEquals(cert1.getSerialNumber(), reloadedServerCert.getSerialNumber());
		X509Certificate reloadedClientCert = (X509Certificate) reloaded1.getCertificate("admin-client");
		assertEquals(clientCert.getSerialNumber(), reloadedClientCert.getSerialNumber());

		KeyStore reloaded2 = KeyStore.getInstance("BCFKS", TlsTestKeystoreFactory.BCFIPS);
		try (var in = new java.io.FileInputStream(file2)) {
			reloaded2.load(in, password);
		}
		X509Certificate reloadedRotatedCert = (X509Certificate) reloaded2.getCertificate("dev.izgateway.org");
		assertEquals(cert2.getSerialNumber(), reloadedRotatedCert.getSerialNumber());
		assertNotEquals(reloadedServerCert.getSerialNumber(), reloadedRotatedCert.getSerialNumber());
	}
}
