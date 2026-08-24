package com.aware.phone.ui.dialogs;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.PermissionChecker;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.phone.ui.Aware_Join_Study;
import com.aware.phone.ui.Aware_QRCode;
import com.aware.phone.ui.SensorConsentActivity;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.StudyUtils;

import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;


/**
 * Manages dialog that is used to join a study by typing in a URL of the study config.
 */
public class JoinStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::JoinStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;
    private String prefillUrl;

    public JoinStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    /**
     * Pre-fills the study URL field. Used to re-join a previously joined study: the dialog is
     * still shown (and thus attached), so the normal, working join path runs on confirm.
     */
    public JoinStudyDialog setStudyUrl(String url) {
        this.prefillUrl = url;
        return this;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        LayoutInflater inflater = mActivity.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.dialog_join_study, null);

        if (prefillUrl != null && prefillUrl.length() > 0) {
            EditText etPrefill = dialogView.findViewById(R.id.et_join_study_url);
            etPrefill.setText(prefillUrl);
        }

        dialogView.findViewById(R.id.btn_scan_study_qr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JoinStudyDialog.this.dismiss();
                openScanner();
            }
        });

        builder.setView(dialogView);
        builder.setTitle("Enter URL for study")
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        EditText etStudyConfigUrl = dialogView.findViewById(R.id.et_join_study_url);
                        EditText dbPassword = dialogView.findViewById(R.id.db_password); // manually input password
                        validateStudy(new ValidationRequest(
                                etStudyConfigUrl.getText().toString(),
                                dbPassword.getText().toString()));
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        JoinStudyDialog.this.dismiss();
                    }
                });
        return builder.create();
    }

    public void showDialog() {
        this.show(mActivity.getFragmentManager(), "dialog");
    }

    /**
     * Open the scanner, asking for the camera at the moment the participant chooses to
     * scan.
     *
     * The permission is requested here rather than at install time because scanning is
     * one of two ways to join: a participant who prefers to paste the link is never
     * asked for the camera at all. {@link PermissionsHandler} returns to the scanner
     * once the grant is settled, so both branches arrive in the same place.
     */
    private void openScanner() {
        String scanner = mActivity.getPackageName() + "/" + mActivity.getPackageName()
                + ".ui.Aware_QRCode";
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || PermissionChecker.checkSelfPermission(mActivity, Manifest.permission.CAMERA)
                        == PermissionChecker.PERMISSION_GRANTED;

        if (granted) {
            Intent qrcode = new Intent(mActivity, Aware_QRCode.class);
            qrcode.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(qrcode);
            return;
        }

        ArrayList<String> permission = new ArrayList<>();
        permission.add(Manifest.permission.CAMERA);

        Intent permissions = new Intent(mActivity, PermissionsHandler.class);
        permissions.putExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, permission);
        permissions.putExtra(PermissionsHandler.EXTRA_REDIRECT_ACTIVITY, scanner);
        permissions.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivity.startActivity(permissions);
    }

    /**
     * Everything {@link ValidateStudyConfig} needs to validate one study, in a single object.
     *
     * Replaces the old {@code String...} varargs contract: the deeplink and QR-code entry points
     * started the task with the URL alone while the task unconditionally read {@code strings[1]} for
     * the password, so those paths died with {@code ArrayIndexOutOfBoundsException}. A participant
     * who supplied no password is now represented explicitly by an empty string, never by a missing
     * array element, and there is no constructor that lets a caller omit either field.
     */
    public static final class ValidationRequest {
        private final String studyUrl;
        private final String inputPassword;

        public ValidationRequest(String studyUrl, String inputPassword) {
            this.studyUrl = studyUrl == null ? "" : studyUrl.trim();
            this.inputPassword = inputPassword == null ? "" : inputPassword;
        }
    }

    /**
     * Starts validation for a study whose config has not been fetched yet and for which the
     * participant has supplied no password — the {@code aware://} deeplink and QR-code entry points.
     */
    public void validateStudy(String studyUrl) {
        validateStudy(new ValidationRequest(studyUrl, ""));
    }

    public void validateStudy(ValidationRequest request) {
        new ValidateStudyConfig(request).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Accepts an http or https URL with a host — which is exactly what {@link
     * StudyUtils#getStudyConfig} can fetch, since OkHttp's {@code Request.Builder#url} rejects every
     * other scheme. So this narrows nothing: it only converts crashes into messages. A null or blank
     * URL (reachable whenever the launching intent carried no study URL) would NPE inside
     * {@code getStudyConfig}, and a scheme-less string would throw {@code IllegalArgumentException}
     * out of OkHttp. {@code aware://} links are rewritten to {@code https://} by
     * {@code Aware_Join_Study} before they reach here.
     *
     * Uses {@code java.net.URI} rather than {@code android.net.Uri} so it stays unit-testable, and
     * reads the authority rather than the host because {@code URI} reports a null host for
     * hostnames containing an underscore, which internal study servers do use.
     */
    static boolean isValidStudyUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            String authority = uri.getAuthority();
            return authority != null && !authority.isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** One validation attempt's outcome; {@code studyConfig} is set only when the result is OK. */
    private static final class Outcome {
        final StudyUtils.StudyConfigValidation result;
        final String studyConfig;

        Outcome(StudyUtils.StudyConfigValidation result, String studyConfig) {
            this.result = result;
            this.studyConfig = studyConfig;
        }

        static Outcome of(StudyUtils.StudyConfigValidation result) {
            return new Outcome(result, null);
        }
    }

    public class ValidateStudyConfig extends AsyncTask<Void, Void, Outcome> {
        private final ValidationRequest request;
        private ProgressDialog mLoader;

        public ValidateStudyConfig(ValidationRequest request) {
            if (request == null) throw new IllegalArgumentException("Validation request is required");
            this.request = request;
        }

        @Override
        protected void onPreExecute() {
            if (mActivity == null || mActivity.isFinishing()) return;
            // mActivity.getString, not Fragment#getResources(): the deeplink and QR-code entry points
            // run this task from a JoinStudyDialog that is never shown, so the fragment has no host
            // and getResources() would throw IllegalStateException before validation even starts.
            mLoader = new ProgressDialog(mActivity);
            mLoader.setTitle(R.string.loading_join_study_title);
            mLoader.setMessage(mActivity.getString(R.string.loading_join_study_msg));
            mLoader.setCancelable(true);
            mLoader.setIndeterminate(true);
            mLoader.show();
        }

        /** No UI work here — this runs off the main thread. Results are typed, never a bare null. */
        @Override
        protected Outcome doInBackground(Void... unused) {
            if (!isValidStudyUrl(request.studyUrl)) {
                Log.d(TAG, "Rejected study URL before fetching: missing or malformed");
                return Outcome.of(StudyUtils.StudyConfigValidation.INVALID_CONFIG);
            }

            Log.i(TAG, "Joining study with URL " + request.studyUrl);
            try {
                JSONObject studyConfig = StudyUtils.getStudyConfig(request.studyUrl);
                if (studyConfig == null) {
                    Log.d(TAG, "No usable study config at: " + request.studyUrl);
                    return Outcome.of(StudyUtils.StudyConfigValidation.INVALID_CONFIG);
                }

                // The application context, not mActivity: validation probes the database, and the
                // trust store it reads for that outlives whichever screen started the join.
                StudyUtils.StudyConfigValidation result = StudyUtils.validateStudyConfigDetailed(
                        mActivity.getApplicationContext(), studyConfig, request.inputPassword);
                if (result != StudyUtils.StudyConfigValidation.OK) {
                    Log.d(TAG, "Failed to join study with URL: " + request.studyUrl
                            + ", reason: " + result);
                    return Outcome.of(result);
                }
                return new Outcome(result, studyConfig.toString());

            } catch (JSONException e) {
                Log.d(TAG, "Failed to join study with URL: " + request.studyUrl
                        + ", reason: " + e.getMessage());
                return Outcome.of(StudyUtils.StudyConfigValidation.INVALID_CONFIG);
            } catch (Exception e) {
                // A controlled failure rather than a crash on the participant's screen. The message
                // is a diagnostic only; the request's password is never part of it.
                Log.e(TAG, "Unexpected failure validating study config: " + e.getClass().getName()
                        + ": " + e.getMessage());
                return Outcome.of(StudyUtils.StudyConfigValidation.UNREACHABLE);
            }
        }

        @Override
        protected void onPostExecute(Outcome outcome) {
            dismissLoader();
            if (isAdded()) JoinStudyDialog.this.dismiss();
            if (mActivity == null || mActivity.isFinishing()) return;

            Log.d(TAG, "Study validation result: " + outcome.result);
            switch (outcome.result) {
                case OK:
                    // Show the sensor consent screen first (it reviews what the study collects and
                    // collects any permission grants), then it hands off to the identifier / sign-up
                    // step. Runs before enrolment — nothing is applied until the participant signs up.
                    Intent consent = new Intent(mActivity, SensorConsentActivity.class);
                    consent.putExtra(Aware_Join_Study.EXTRA_STUDY_URL, request.studyUrl);
                    consent.putExtra(Aware_Join_Study.EXTRA_STUDY_CONFIG, outcome.studyConfig);
                    consent.putExtra(Aware_Join_Study.INPUT_PASSWORD, request.inputPassword);
                    consent.putExtra(SensorConsentActivity.EXTRA_PRE_JOIN, true);
                    consent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    mActivity.startActivity(consent);
                    break;

                case PASSWORD_REQUIRED:
                    // Reached from the entry points that have no password field: return the
                    // participant to an interactive screen instead of a dead end, with the URL
                    // already filled in so they only have to type the password.
                    Toast.makeText(mActivity, "This study needs a password. Please enter the "
                            + "password you received from the study administrator.",
                            Toast.LENGTH_LONG).show();
                    new JoinStudyDialog(mActivity).setStudyUrl(request.studyUrl).showDialog();
                    break;

                case AUTH_FAILED:
                    Toast.makeText(mActivity, "Password not correct", Toast.LENGTH_LONG).show();
                    break;

                case UNREACHABLE:
                    Toast.makeText(mActivity, "Could not reach the study database. Check your "
                            + "internet connection and try again, or contact the administrator of "
                            + "this study.", Toast.LENGTH_LONG).show();
                    break;

                default:
                    Toast.makeText(mActivity, "Invalid study config or no internet. Please contact "
                            + "the administrator of this study or enter a different study URL.",
                            Toast.LENGTH_LONG).show();
                    break;
            }
        }

        /**
         * The loader belongs to an Activity that may already be gone — dismissing a dialog whose
         * window has been torn down throws {@code IllegalArgumentException: View not attached to
         * window manager}. The guards keep the common cases quiet; the catch covers Activity
         * recreation, where {@code isFinishing()} is false but the window is already detached. The
         * real fix is to move progress into the Activity's own layout (plan item 2).
         */
        private void dismissLoader() {
            if (mLoader == null || !mLoader.isShowing()) return;
            try {
                mLoader.dismiss();
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Loader's window was already gone: " + e.getMessage());
            }
            mLoader = null;
        }
    }
}
