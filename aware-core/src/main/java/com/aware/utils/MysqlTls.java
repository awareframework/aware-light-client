package com.aware.utils;

import android.content.Context;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Supplies the TLS parameters the MySQL driver uses when it opens a connection to the research
 * database.
 *
 * TLS carries two guarantees: the traffic is unreadable in transit, and the host that answers is the
 * one the study means to reach. The traffic is always encrypted. The second guarantee needs an
 * authority to check the offered certificate against, and that authority belongs to the study, not
 * to the app — one build of the app serves many studies, each with its own database. So a study
 * publishes its authority in its configuration, where {@link StudyUtils} stores it under
 * {@link Aware_Preferences#DB_CA}.
 *
 * A study that publishes one gets a verified connection: a certificate from any other authority ends
 * the handshake, which confines the upload to that study's own server rather than to whichever host
 * occupies the network path. A study that publishes none gets an encrypted but unverified connection
 * — the traffic is unreadable in transit, but nothing proves which host is reading it.
 *
 * The driver reads its trust store from a URL, so the configured certificate is turned into a PKCS#12
 * file in the app's private storage and reused from there. The file name carries a digest of the
 * certificate, so a study that rotates its authority, or a device that moves between studies, builds
 * a separate store rather than reusing a stale one.
 */
public final class MysqlTls {

    private static final String TAG = "MysqlTls";

    /** Alias the authority's certificate is stored under. */
    private static final String CA_ALIAS = "study-database-ca";

    /**
     * Integrity password for the trust store file. A trust store holds certificates that are public
     * by nature — this one ships inside the APK — and PKCS#12 asks for a password to open a store at
     * all.
     */
    static final String TRUST_STORE_PASSWORD = "awaretruststore";

    /** Characters of the certificate digest that name the store file. */
    private static final int DIGEST_CHARS_IN_NAME = 16;

    /**
     * The parameters last built, together with the certificate they were built from. Keyed by the
     * certificate so a study that publishes a new authority is picked up on the next connection
     * rather than after the process restarts.
     */
    private static volatile String cachedForCertificate;
    private static volatile String cachedParameters;

    private MysqlTls() {
    }

    /**
     * Name of the trust store holding a certificate with the given digest. Pure and side-effect free
     * so it can be unit-tested without a device.
     *
     * @param digestHex hex digest of the certificate's encoded form
     * @return a file name that belongs to that certificate alone
     */
    static String trustStoreName(String digestHex) {
        return "mysql_truststore_" + digestHex.substring(0, DIGEST_CHARS_IN_NAME) + ".p12";
    } 

    /**
     * The JDBC URL query parameters that have the driver verify the server's certificate against a
     * trust store. Pure and side-effect free so it can be unit-tested without a device.
     *
     * @param trustStorePath absolute path of the trust store file
     * @return a fragment opening with {@code &}, to append to a JDBC URL that already carries a query
     */
    static String sslParameters(String trustStorePath) {
        return "&useSSL=true&requireSSL=true&verifyServerCertificate=true"
                + "&trustCertificateKeyStoreType=PKCS12"
                + "&trustCertificateKeyStoreUrl=file:" + trustStorePath
                + "&trustCertificateKeyStorePassword=" + TRUST_STORE_PASSWORD;
    }

    /**
     * The JDBC URL query parameters for an encrypted connection whose certificate is not checked,
     * which is what a study that publishes no authority gets. Pure and side-effect free so it can be
     * unit-tested without a device.
     *
     * @return a fragment opening with {@code &}, to append to a JDBC URL that already carries a query
     */
    static String unverifiedParameters() {
        return "&useSSL=true&requireSSL=true&verifyServerCertificate=false";
    }

    /**
     * The TLS parameters for the study this device is enrolled in: verified against the study's own
     * authority when it publishes one, encrypted but unverified when it does not.
     *
     * The caller decides what a failure here means: every connection path treats it the same way as a
     * database it could not reach, so a batch is kept for the next sync and the participant is left
     * alone. A study that publishes an unreadable certificate therefore stops uploading rather than
     * quietly falling back to an unverified connection — a configured authority that cannot be honoured
     * is a problem to surface, not to work around.
     *
     * @param context application context
     * @return the fragment described by {@link #sslParameters} or {@link #unverifiedParameters}
     * @throws IOException              the trust store file is unreadable
     * @throws GeneralSecurityException the configured certificate cannot be read, digested or stored
     */
    public static String connectionParameters(Context context)
            throws IOException, GeneralSecurityException {
        String pem = Aware.getSetting(context, Aware_Preferences.DB_CA);
        if (pem == null) pem = "";
        pem = pem.trim();

        if (pem.isEmpty()) {
            Log.i(TAG, "Study publishes no database certificate authority; "
                    + "the upload is encrypted but the server is not verified.");
            return unverifiedParameters();
        }

        if (pem.equals(cachedForCertificate) && cachedParameters != null) return cachedParameters;

        Certificate authority = readCertificate(pem);
        File store = new File(context.getFilesDir(), trustStoreName(digestOf(authority)));
        if (!store.exists()) writeTrustStore(authority, store);

        String parameters = sslParameters(store.getAbsolutePath());
        cachedParameters = parameters;
        cachedForCertificate = pem;
        return parameters;
    }

    /** Reads a PEM-encoded certificate as the study published it. */
    private static Certificate readCertificate(String pem)
            throws IOException, GeneralSecurityException {
        InputStream certificate = new ByteArrayInputStream(pem.getBytes("UTF-8"));
        try {
            return CertificateFactory.getInstance("X.509").generateCertificate(certificate);
        } finally {
            certificate.close();
        }
    }

    /** Hex SHA-256 of a certificate's encoded form, which names the store built from it. */
    private static String digestOf(Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /** Writes a trust store whose sole entry is the study's certificate authority. */
    private static void writeTrustStore(Certificate authority, File store)
            throws IOException, GeneralSecurityException {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry(CA_ALIAS, authority);

        OutputStream out = new FileOutputStream(store);
        try {
            trust.store(out, TRUST_STORE_PASSWORD.toCharArray());
        } finally {
            out.close();
        }
    }
}
