package com.aware.phone.ui.dialogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link JoinStudyDialog#isValidStudyUrl(String)} — the guard that runs before a
 * study config is fetched.
 *
 * Every rejection here used to be a crash rather than a message: a null study URL (the launching
 * intent carried none) threw NPE inside StudyUtils.getStudyConfig, and a scheme-less string threw
 * IllegalArgumentException out of OkHttp's Request.Builder. The accepted set is deliberately
 * http/https-with-a-host, which is exactly what OkHttp can fetch — aware:// deeplinks are rewritten
 * to https:// by Aware_Join_Study before they reach this code.
 */
public class StudyUrlValidationTest {

    @Test
    public void httpsUrlIsAccepted() {
        assertTrue(JoinStudyDialog.isValidStudyUrl("https://study.example.org/config.json"));
    }

    @Test
    public void plainHttpUrlIsAccepted() {
        // Researchers do host configs on plain http; rejecting it here would break those studies.
        assertTrue(JoinStudyDialog.isValidStudyUrl("http://study.example.org/config.json"));
    }

    @Test
    public void schemeIsCaseInsensitive() {
        assertTrue(JoinStudyDialog.isValidStudyUrl("HTTPS://study.example.org/config.json"));
    }

    @Test
    public void hostWithUnderscoreIsAccepted() {
        // java.net.URI reports a null host for these, which is why the check reads the authority.
        assertTrue(JoinStudyDialog.isValidStudyUrl("https://study_server.example.org/config.json"));
    }

    @Test
    public void portAndQueryAreAccepted() {
        assertTrue(JoinStudyDialog.isValidStudyUrl("https://example.org:8443/index.php?id=7"));
    }

    @Test
    public void googleDriveShareLinkIsAccepted() {
        // getStudyConfig rewrites these into direct-download URLs, so they must survive the guard.
        assertTrue(JoinStudyDialog.isValidStudyUrl(
                "https://drive.google.com/file/d/1AbCdEf/view?usp=sharing"));
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        // A QR scan or a paste can carry trailing whitespace; that is not a malformed URL.
        assertTrue(JoinStudyDialog.isValidStudyUrl("  https://study.example.org/config.json\n"));
    }

    @Test
    public void nullUrlIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl(null));
    }

    @Test
    public void emptyUrlIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl(""));
    }

    @Test
    public void blankUrlIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl("   "));
    }

    @Test
    public void schemelessUrlIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl("study.example.org/config.json"));
    }

    @Test
    public void nonHttpSchemeIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl("ftp://study.example.org/config.json"));
        assertFalse(JoinStudyDialog.isValidStudyUrl("file:///sdcard/config.json"));
    }

    @Test
    public void unconvertedDeeplinkIsRejected() {
        // Aware_Join_Study is responsible for rewriting aware:// to https://. If that ever stops
        // happening, this must fail as an invalid config rather than crash inside OkHttp.
        assertFalse(JoinStudyDialog.isValidStudyUrl("aware://study.example.org/config.json"));
    }

    @Test
    public void schemeWithoutHostIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl("https://"));
    }

    @Test
    public void malformedUrlIsRejected() {
        assertFalse(JoinStudyDialog.isValidStudyUrl("https://exa mple.org/config.json"));
    }
}
