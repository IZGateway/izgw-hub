package test.gov.cdc.izgateway.security.ocsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import gov.cdc.izgateway.Application;
import gov.cdc.izgateway.db.model.CertificateStatus;
import gov.cdc.izgateway.db.repository.CertificateStatusRepository;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.security.ocsp.CertificateStatusType;
import gov.cdc.izgateway.security.ocsp.RevocationChecker;
import gov.cdc.izgateway.security.ocsp.RevocationChecker.SslLocation;
import gov.cdc.izgateway.service.CertificateStatusService;
import gov.cdc.izgateway.service.ICertificateStatusService;
import gov.cdc.izgateway.utils.X500Utils;
import lombok.extern.slf4j.Slf4j; 
/**
 * We need to override certain JDBC Properties to use the H2 in memory database for this test.
 * These properties ensure that we use H2, and that the schemas are automatically
 * loaded into the database.
 */
@Slf4j
@ExtendWith(SpringExtension.class)
@EnableWebSecurity
@DataJpaTest(properties = {
		// Comment these out to test with your local JDBC Database instead of H2
		"spring.datasource.url=jdbc:h2:mem:unit-testing-jpa-hub2x",
		"spring.datasource.username=sa",
		"spring.datasource.password.hub.pass=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.defer-datasource-initialization=false",
		"hibernate.hbm2ddl.auto=create-drop",
		"spring.sql.init.mode=always",
		// Uncomment these to test with your local JDBC Database 
//		"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
//		"spring.datasource.url=jdbc:mysql://localhost:3306/phiz",
//		"spring.datasource.username=remoteHub",
//		"spring.datasource.password=<PASSWORD>", // Replace <PASSWORD> with your local database password.
		// Change to MySQL8Dialect for local db
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
	})
@ContextConfiguration(classes=Application.class)

/**
 * Performs revocation checks on all certs in /test/resources/revocationCheck.jks 
 * (except for phiz-ca), which this test uses as the issuer for all tested certs.
 * 
 * Verifies appropriate responses for good, revoked and a cert with no url to perform
 * revocation checks on.
 *
 */
class TestRevocationChecker implements InitializingBean {
	private static final int DELAY = 1;
	private static int REPEAT = 0;
	static X509Certificate phizca;
	static String lastAlias;

	@Autowired 
	CertificateStatusRepository certificateStatusRepository;
	ICertificateStatusService certificateStatusService;
	
    public TestRevocationChecker() {
    }

    public void afterPropertiesSet() {
    	log.info("Startup");
    	this.certificateStatusService = new CertificateStatusService(certificateStatusRepository);
    }
    
	/**
     * Ensure that the revocation checker produces the correct result for each certificate
     * in resources/revocationCheck.jks. That file should contain one certificate for each
     * type of expected result (good, revoked, and null).  Nothing should return an unknown
     * certificate status.
     *  
     * @param alias	Alias should match the result, or be no_url if a null 
     * @param cert
     * @throws Exception
     */
	@ParameterizedTest
	@MethodSource("getCerts")
	void testCertificateRevocationChecker(String alias, X509Certificate cert) throws Exception {
		if (alias.equals(lastAlias)) {
			// If performing repeated tests, delay between each test.
			Thread.sleep(DELAY);
		}
		lastAlias = alias;

		RevocationChecker chk = new RevocationChecker(certificateStatusService, new SecureRandom());

		chk.setConnectTimeout(5000);
		chk.setNonceOptional(true);
		chk.setNonceSize(32);
		chk.setReadTimeout(5000);
		
		CertificateStatusType result = chk.revocationCheck(SslLocation.CLIENT, cert, phizca);
		if (result == null) {
			assertEquals("no_url", alias);
		} else {
			String cn = X500Utils.getCommonName(cert);
			log.info("{} {}: {}", alias, cn, result);
			if (alias.toUpperCase().equals(CertificateStatusType.REVOKED.toString()) && result.equals(CertificateStatusType.GOOD)) {
				System.err.printf("Certificate for %s has not been revoked yet%n", cn);
			}
			assertEquals(alias.toUpperCase(), result.toString());
		}
	}
	
	/**
	 * Test database service persisting certificates
	 * @param alias	The certificate alias
	 * @param cert The certificate
	 * @throws Exception If an error occurs during persistence
	 */
	@ParameterizedTest
	@MethodSource("getCerts")
	void testDBService(String alias, X509Certificate cert) throws Exception {
		CertificateStatus certStatus = CertificateStatus.create(cert);
		
		ICertificateStatus result = certificateStatusService.findByCertificateId(certStatus.getCertificateId());
		
		assertNull(result, "Certificate should not be found for " + alias);
		
		result = certificateStatusService.save(certStatus);
		
		// Two objects should be equal
		assertEquals(certStatus, result);
		// But distinct
		assertNotSame(certStatus, result);
		result = certificateStatusService.findByCertificateId(certStatus.getCertificateId());
		// Two objects should be distinct
		assertNotSame(certStatus, result);
		// And objects should be equal, but if result is UNKNOWN, this should only generate a warning
		try {
			assertEquals(certStatus, result);
		} catch (AssertionError e) {
			if (!CertificateStatusType.UNKNOWN.toString().equals(result.getLastCheckStatus())) {
				throw e;
			}
			// Don't fail on UNKNOWN, as it may be OCSP responder is not available.
			System.err.println("Certificate Status UNKNOWN, is OCSP Responder Available?");
		}
	}
	
	private static final int MAXTHREADS = 10;
	@Test
	void testMultithreadedAccess() {
		long interval = 24 * 3600 * 1000;
		// Attempt to create 10 threads that update the database at 
		// the same time, and ensure that all succeed, even with delays.
		List<CertificateStatus> l = new ArrayList<>();
		for (int i = 0; i < MAXTHREADS; i++) {
			CertificateStatus status = new CertificateStatus();
			status.setCertificateId(String.format("%02x", i));
			status.setCertSerialNumber(String.format("%02d", i));
			status.setCommonName(String.format("%d", i));
			long now = System.currentTimeMillis();
			status.setLastCheckedTimeStamp(new Timestamp(now));
			status.setNextCheckTimeStamp(new Timestamp(now + interval));
			status.setLastCheckStatus("GOOD" + i);
			l.add(status);
		}
		ExecutorService s = Executors.newFixedThreadPool(MAXTHREADS);
		for (CertificateStatus status: l) {
			s.execute(() -> pretendToCheckCertificate(status));
		}
		s.shutdown();
		try {
			s.awaitTermination(1, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		assertTrue(s.isTerminated(), "All threads did not complete in one minute");
		for (int i = 0; i < MAXTHREADS; i++) {
			ICertificateStatus status = certificateStatusService.findByCertificateId(String.format("%02x", i));
			System.out.println(status);
		}
	}
	
	void pretendToCheckCertificate(CertificateStatus status) {
		ICertificateStatus found = certificateStatusService.findByCertificateId(status.getCertificateId());
		assertNull(found, "Should not find a certificate for initial test");
		// Add delay to simulate delays performing the test.
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		status.setLastCheckedTimeStamp(new Timestamp(System.currentTimeMillis()));
		certificateStatusService.save(status);
		// Add delay to simulate delays performing the upload.
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
	
	private static KeyStore getKeyStore() {
		try {
			KeyStore ks = KeyStore.getInstance("jks");
			char[] password = {};
			InputStream is = TestRevocationChecker.class.getResourceAsStream("/revocationCheck.jks");
			ks.load(is, password);
			phizca = (X509Certificate) ks.getCertificate("phiz-ca");
			return ks;
		} catch (Exception e) {
			throw new RuntimeException("Error loading Keystore", e);
		}
	}

	public static Stream<Object[]> getCerts() throws KeyStoreException {
		List<Object[]> l = new ArrayList<>();
		KeyStore ks = getKeyStore();
		Enumeration<String> a = ks.aliases();
		while (a.hasMoreElements()) {
			String alias = a.nextElement();
			X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
			if (alias.equals("phiz-ca")) {
				continue;
			}
			Date now = new Date();
			if (now.after(cert.getNotAfter())) {
				// This certificate has expired.
				log.error("Test certificate has expired: {}", cert);
				System.err.println("Test certificate for " + alias + " has expired: " + cert.toString());
				continue;
			}
			Object[] o = { alias, cert };
			l.add(o);
			if ("revoked".equals(alias) && REPEAT > 0) {
				// Repeat tests on revoked cert to ensure consistent response
				// from OCSP responder.
				for (int i = 0; i < REPEAT; i++) {
					l.add(o);
				}
			}
		}
		return l.stream();
	}
}
