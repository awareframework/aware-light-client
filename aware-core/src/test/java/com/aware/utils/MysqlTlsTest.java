package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the parts of {@link MysqlTls} that decide what the driver is told, which is where a mistake
 * would be quiet: a URL that parses but leaves out the verification flag connects perfectly well to
 * any host that answers, so these assertions pin the exact parameters rather than a shape.
 */
public class MysqlTlsTest {

    private static final String STORE = "/data/user/0/com.aware.phone/files/mysql_truststore_ab.p12";

    @Test
    public void parametersDemandCertificateVerification() {
        assertTrue(MysqlTls.sslParameters(STORE).contains("verifyServerCertificate=true"));
    }

    @Test
    public void parametersRequireTlsForTheConnection() {
        String parameters = MysqlTls.sslParameters(STORE);
        assertTrue(parameters.contains("useSSL=true"));
        assertTrue(parameters.contains("requireSSL=true"));
    }

    @Test
    public void parametersPointAtTheTrustStoreAsAFileUrl() {
        assertTrue(MysqlTls.sslParameters(STORE).contains("trustCertificateKeyStoreUrl=file:" + STORE));
    }

    @Test
    public void parametersNameTheKeystoreFormatAndPassword() {
        String parameters = MysqlTls.sslParameters(STORE);
        assertTrue(parameters.contains("trustCertificateKeyStoreType=PKCS12"));
        assertTrue(parameters.contains("trustCertificateKeyStorePassword=" + MysqlTls.TRUST_STORE_PASSWORD));
    }

    @Test
    public void parametersAppendToAUrlThatAlreadyHasAQuery() {
        // The three call sites all format these onto a URL ending in a parameter, so the fragment
        // opens with a separator rather than a '?'.
        assertTrue(MysqlTls.sslParameters(STORE).startsWith("&"));
    }

    @Test
    public void storeNameIsDerivedFromTheCertificateDigest() {
        assertEquals("mysql_truststore_7ee0a4481f6b5227.p12",
                MysqlTls.trustStoreName("7ee0a4481f6b5227d1de4a41424bacab0d8123394ac0d604c208dbe367dec1aa"));
    }

    @Test
    public void adifferentAuthorityGetsADifferentStore() {
        String one = MysqlTls.trustStoreName("1111111111111111aaaaaaaaaaaaaaaa");
        String two = MysqlTls.trustStoreName("2222222222222222aaaaaaaaaaaaaaaa");
        assertTrue(!one.equals(two));
    }

    @Test
    public void storeNameIsAValidFileNameForPrivateStorage() {
        String name = MysqlTls.trustStoreName("7ee0a4481f6b5227d1de4a41424bacab");
        assertTrue(name.matches("[a-z0-9_]+\\.p12"));
    }

    @Test
    public void aStudyWithoutAnAuthorityStillEncryptsTheConnection() {
        // No authority means the server cannot be identified, but the traffic must not travel in the
        // clear on that account — and the study's account may require TLS regardless.
        String parameters = MysqlTls.unverifiedParameters();
        assertTrue(parameters.contains("useSSL=true"));
        assertTrue(parameters.contains("requireSSL=true"));
    }

    @Test
    public void aStudyWithoutAnAuthorityDoesNotClaimToVerify() {
        assertTrue(MysqlTls.unverifiedParameters().contains("verifyServerCertificate=false"));
    }

    @Test
    public void theUnverifiedFragmentNamesNoTrustStore() {
        // Naming a store while verification is off reads as though the store were being honoured.
        assertFalse(MysqlTls.unverifiedParameters().contains("trustCertificateKeyStore"));
    }

    @Test
    public void bothFragmentsAppendToAUrlThatAlreadyHasAQuery() {
        assertTrue(MysqlTls.unverifiedParameters().startsWith("&"));
        assertTrue(MysqlTls.sslParameters(STORE).startsWith("&"));
    }

    @Test
    public void theTwoModesDisagreeOnlyOnVerification() {
        // The difference between a study that publishes an authority and one that does not has to be
        // verification alone; a fragment that also dropped useSSL would send data in the clear.
        assertTrue(MysqlTls.sslParameters(STORE).contains("verifyServerCertificate=true"));
        assertTrue(MysqlTls.unverifiedParameters().contains("verifyServerCertificate=false"));
    }
}
