package gov.cdc.izgateway;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates ephemeral, in-memory-only self-signed certificates and BCFKS keystores for the
 * TLS certificate hot-reload verification test (IGDD-3582). Nothing produced here is ever
 * checked into the repository -- every run generates fresh key material so the test fixtures
 * can never expire, unlike a committed keystore file would.
 */
final class TlsTestKeystoreFactory {
	static final String BCFIPS = "BCFIPS";

	static {
		if (Security.getProvider(BCFIPS) == null) {
			Security.addProvider(new BouncyCastleFipsProvider());
		}
	}

	private TlsTestKeystoreFactory() {}

	static KeyPair generateKeyPair() throws GeneralSecurityException {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		return kpg.generateKeyPair();
	}

	/**
	 * Build the X500Name used for the server identity certificate, matching the real
	 * {@code awsdev_keystore.bcfks} fixture's DN (minus the email attribute, which is not
	 * behaviorally relevant to anything under test).
	 */
	static X500Name serverDn() {
		return new X500NameBuilder(BCStyle.INSTANCE)
				.addRDN(BCStyle.CN, "dev.izgateway.org")
				.addRDN(BCStyle.OU, "Federal and Strategic Advisory")
				.addRDN(BCStyle.O, "Audacious Inquiry")
				.addRDN(BCStyle.L, "Baltimore")
				.addRDN(BCStyle.ST, "Maryland")
				.addRDN(BCStyle.C, "US")
				.build();
	}

	/**
	 * Build the X500Name for the admin test client certificate. The CN matches the
	 * {@code *.testing.izgateway.org} wildcard seeded into the ADMIN group in
	 * {@code src/test/resources/data.sql}, so this identity is granted ADMIN access under
	 * the JPA-backed test access-control data with no additional role wiring.
	 */
	static X500Name clientDn() {
		return new X500NameBuilder(BCStyle.INSTANCE)
				.addRDN(BCStyle.CN, "tls-hot-reload-test.testing.izgateway.org")
				.addRDN(BCStyle.OU, "Testing")
				.addRDN(BCStyle.O, "IZ Gateway")
				.addRDN(BCStyle.L, "Baltimore")
				.addRDN(BCStyle.ST, "Maryland")
				.addRDN(BCStyle.C, "US")
				.build();
	}

	static X509Certificate selfSign(KeyPair keyPair, X500Name dn, int validityDays) throws Exception {
		Date notBefore = new Date();
		Date notAfter = new Date(notBefore.getTime() + validityDays * 86_400_000L);
		BigInteger serial = BigInteger.valueOf(System.nanoTime());
		X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
				dn, serial, notBefore, notAfter, dn, keyPair.getPublic());
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
		return new JcaX509CertificateConverter().setProvider(BCFIPS).getCertificate(builder.build(signer));
	}

	/**
	 * Create a BCFKS keystore containing the given server identity as a single
	 * {@code PrivateKeyEntry}.
	 */
	static KeyStore newServerKeyStore(String alias, KeyPair keyPair, X509Certificate cert, char[] password)
			throws GeneralSecurityException {
		KeyStore ks = KeyStore.getInstance("BCFKS", BCFIPS);
		try {
			ks.load(null, null);
		} catch (Exception e) {
			throw new GeneralSecurityException(e);
		}
		ks.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[] { cert });
		return ks;
	}

	/** Add a trusted certificate entry (e.g. the admin client cert) to an existing keystore. */
	static void addTrustedCert(KeyStore ks, String alias, X509Certificate cert) throws GeneralSecurityException {
		ks.setCertificateEntry(alias, cert);
	}

	static void writeKeyStore(KeyStore ks, File file, char[] password) throws Exception {
		try (OutputStream out = new FileOutputStream(file)) {
			ks.store(out, password);
		}
	}
}
