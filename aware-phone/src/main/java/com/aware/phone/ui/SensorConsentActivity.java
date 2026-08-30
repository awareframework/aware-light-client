package com.aware.phone.ui;

import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.phone.ui.prefs.SensorCollection;
import com.aware.phone.ui.prefs.SensorCollection.ConsentItem;
import com.aware.providers.Aware_Provider;
import com.aware.utils.StudyUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-join consent screen: lists the sensors this study would enable that need a runtime permission,
 * one row at a time, each with an Enable button (or "Granted ✓"). Tapping Enable requests just that
 * sensor's permission(s), so the OS shows them one sensor at a time instead of many services each
 * firing their own request and stacking.
 *
 * Runs before the study's settings are applied: a sensor left ungranted when the participant taps
 * Continue is never turned on at all, rather than being turned on and then possibly off again.
 */
public class SensorConsentActivity extends AppCompatActivity {

    /**
     * When true, this screen is a mid-study re-consent for sensors the researcher added and that were
     * held off (declined) until the participant agrees — not a fresh join. The participant is already
     * enrolled, so there is no enrolment to roll back, and only the held-off sensors are shown.
     */
    public static final String EXTRA_UPDATE_MODE = "update_mode";

    /**
     * When true, this screen runs BEFORE the participant enters their identifier and signs up (the
     * new onboarding order): it collects the grant-requiring permissions, discloses the
     * automatically-collected sensors, records the consent, then hands off to
     * {@link Aware_Join_Study} — it does NOT apply the study or enrol here. When false (legacy /
     * update flows) the screen applies the study and finishes into the app as before.
     */
    public static final String EXTRA_PRE_JOIN = "pre_join";

    private LinearLayout list;
    private List<ConsentItem> items;
    // The full list rendered on screen. A rows (requiresGrant) map back to an entry in {@link #items} for the grant flow. Built once in onCreate.
    private List<SensorCollection.ConsentRow> displayRows;

    private boolean updateMode;
    private boolean preJoin;
    private String studyUrl;
    private String inputPassword;
    private JSONArray studyConfigs;

    // Rows the participant has explicitly acted on in this screen. Several rows can share the same
    // underlying OS gate (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and Bluetooth; the single
    // Accessibility Service toggle backs Applications usage and Keyboard), so checking the OS state
    // alone would silently mark every row sharing that gate as consented the moment one of them is
    // granted. Consent is tracked per row instead, only set once that row's own action completes.
    private final Set<String> consentedKeys = new HashSet<>();

    // Which accessibility row's Enable button was last tapped, so the onResume recheck (there's no
    // onRequestPermissionsResult callback for a Settings-app toggle) attributes the enabled service
    // back to that row specifically, not to every accessibility row at once.
    private String pendingAccessibilityKey;

    // Android 13+ blocks the Accessibility toggle for sideloaded apps until the participant manually
    // allows it from the app's info screen. Shown once per screen instance, for any accessibility row.
    private boolean shownRestrictedSettingsHelp;

    // The "Allow all the time" (background location) nudge is shown at most once per screen instance.
    private boolean shownAlwaysLocationHelp;

    // Bundle keys used to carry the above state across a process death while the participant is away
    // in the Settings app, so their prior taps and one-time dialogs aren't forgotten on return.
    private static final String STATE_CONSENTED_KEYS = "consented_keys";
    private static final String STATE_PENDING_ACCESSIBILITY_KEY = "pending_accessibility_key";
    private static final String STATE_SHOWN_RESTRICTED_HELP = "shown_restricted_settings_help";
    private static final String STATE_SHOWN_ALWAYS_LOCATION_HELP = "shown_always_location_help";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_consent);

        updateMode = getIntent().getBooleanExtra(EXTRA_UPDATE_MODE, false);
        preJoin = getIntent().getBooleanExtra(EXTRA_PRE_JOIN, false);
        studyConfigs = new JSONArray();
        if (updateMode) {
            // Mid-study re-consent: the participant is already enrolled, so read the study they're in
            // (its live config, URL and DB password) rather than taking them from the launch intent.
            JSONObject activeConfig = Aware.getActiveStudyConfig(getApplicationContext());
            if (activeConfig != null) studyConfigs.put(activeConfig);
            studyUrl = activeStudyUrl();
            inputPassword = Aware.getSetting(getApplicationContext(), Aware_Preferences.DB_PASSWORD);
        } else {
            studyUrl = getIntent().getStringExtra(Aware_Join_Study.EXTRA_STUDY_URL);
            inputPassword = getIntent().getStringExtra(Aware_Join_Study.INPUT_PASSWORD);
            try {
                studyConfigs.put(new JSONObject(getIntent().getStringExtra(Aware_Join_Study.EXTRA_STUDY_CONFIG)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        list = findViewById(R.id.consent_list);
        findViewById(R.id.btn_continue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onContinue();
            }
        });
        findViewById(R.id.btn_dont_join).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelJoin();
            }
        });

        if (updateMode) {
            // Mid-study re-consent: show only the sensors the researcher added that are held off,
            // and reframe the screen — the participant is already enrolled, so "don't join" becomes
            // "not now" and declining just leaves those sensors off.
            ((TextView) findViewById(R.id.consent_title)).setText("Study updated");
            ((TextView) findViewById(R.id.consent_subtitle)).setText(
                    "The researcher added sensors to this study. Enable the ones you agree to share, or leave them off — you can enable them later from the sensor list.");
            ((Button) findViewById(R.id.btn_dont_join)).setText("Not now");
            items = SensorCollection.heldConsentsForConfig(getApplicationContext(), studyConfigs);
            // Update mode shows only the held (grant-requiring) sensors
            displayRows = rowsFromItems(items);
        } else {
            items = SensorCollection.enabledConsentsForConfig(studyConfigs);
            // Fresh join: show everything the study collects — grant-requiring and automatic.
            displayRows = SensorCollection.consentRowsForConfig(studyConfigs);
            ((TextView) findViewById(R.id.consent_title)).setText("Data this study collects");
            ((TextView) findViewById(R.id.consent_subtitle)).setText(
                    "Review what this study collects. Sensors marked “Needs permission” ask for your "
                            + "consent when you tap Enable; the rest are collected automatically once you join. "
                            + "Agreeing continues to the last step.");
            ((Button) findViewById(R.id.btn_continue)).setText("Agree & continue");
        }

        // Nothing to show: update mode → back to app; fresh join → straight on to the identifier step
        // (pre-join) or apply directly (legacy post-join). "Nothing" means no rows at all — a study
        // sensors still shows the screen so they are disclosed.
        if (displayRows.isEmpty()) {
            if (updateMode) { goToMainUi(); return; }
            if (preJoin) { forwardToJoinStudy(); return; }
            applyAndFinish(Collections.<String>emptySet());
            return;
        }

        if (savedInstanceState != null) {
            // Coming back after a process death: restore the per-row consent exactly as it was.
            // Re-deriving it from live OS state here would be wrong, because several rows share one
            // gate (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and Bluetooth; the Accessibility
            // toggle backs Applications and Keyboard) — a permission granted for one tapped row would
            // otherwise silently mark every row sharing it as consented.
            restoreInstanceState(savedInstanceState);
        } else {
            // A row already satisfied before this screen ever opened (e.g. granted during an earlier
            // study join) doesn't need a redundant tap. Snapshotted once, here, rather than re-checked
            // on every buildRows(): a permission that only becomes satisfied DURING this screen because
            // another row's tap happens to share it (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and
            // Bluetooth alike) must still go through that row's own tap, not get a free pass.
            for (ConsentItem item : items) {
                if (SensorCollection.isAlreadyGranted(getApplicationContext(), item)) {
                    consentedKeys.add(item.key);
                }
            }
        }
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        SensorCollection.ConsentGroup currentGroup = null;
        for (SensorCollection.ConsentRow row : displayRows) {
            if (row.group != currentGroup) {
                currentGroup = row.group;
                View section = inflater.inflate(
                        R.layout.item_consent_section, (ViewGroup) list, false);
                ((TextView) section.findViewById(R.id.consent_section_title)).setText(
                        currentGroup == SensorCollection.ConsentGroup.PLUGIN
                                ? "Plugins" : "Sensors");
                list.addView(section);
            }
            View view = inflater.inflate(R.layout.item_consent_row, (ViewGroup) list, false);
            ((TextView) view.findViewById(R.id.consent_emoji)).setText(row.emoji);
            ((TextView) view.findViewById(R.id.consent_label)).setText(row.label);
            ((TextView) view.findViewById(R.id.consent_description)).setText(row.description);
            ((TextView) view.findViewById(R.id.consent_badge)).setText(badgeText(row.badge));

            Button enable = view.findViewById(R.id.btn_enable);
            TextView granted = view.findViewById(R.id.txt_granted);

            if (!row.requiresGrant()) {
                // collected automatically once the study starts; nothing to grant.
                enable.setVisibility(View.GONE);
                granted.setVisibility(View.GONE);
            } else {
                final ConsentItem item = row.grantItem;
                final int requestCode = indexOfItem(item.key);
                if (consentedKeys.contains(item.key)) {
                    enable.setVisibility(View.GONE);
                    granted.setVisibility(View.VISIBLE);
                } else {
                    enable.setVisibility(View.VISIBLE);
                    granted.setVisibility(View.GONE);
                    enable.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (item.needsAccessibility) {
                                enableAccessibility(item);
                            } else {
                                ActivityCompat.requestPermissions(SensorConsentActivity.this, item.permissions, requestCode);
                            }
                        }
                    });
                }
            }
            list.addView(view);
        }
    }

    /** Index of the consent item with {@code key} in {@link #items} — the request code its grant uses. */
    private int indexOfItem(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private String badgeText(SensorCollection.ConsentBadge badge) {
        switch (badge) {
            case PERMISSION: return "Needs permission";
            case ACCESSIBILITY: return "Accessibility";
            default: return "Automatic";
        }
    }

    /** Wrap grant-requiring consent items into display rows (used by the mid-study update list). */
    private List<SensorCollection.ConsentRow> rowsFromItems(List<ConsentItem> src) {
        List<SensorCollection.ConsentRow> out = new ArrayList<>();
        for (ConsentItem item : src) out.add(SensorCollection.rowFor(item));
        return out;
    }

    private void enableAccessibility(ConsentItem item) {
        // The accessibility service is a single OS toggle shared by every accessibility-backed sensor
        // (Applications usage, Keyboard, Screenshots). If it's already on — e.g. the participant just
        // enabled it for another such row — this row's requirement is already met, so tapping Enable
        // is itself the consent; don't send them back to Accessibility settings.
        if (SensorCollection.isAccessibilityServiceEnabled(getApplicationContext())) {
            consentedKeys.add(item.key);
            buildRows();
            return;
        }
        pendingAccessibilityKey = item.key;
        if (Build.VERSION.SDK_INT >= 33 && !shownRestrictedSettingsHelp) {
            showRestrictedSettingsDialog();
        } else {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void showRestrictedSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("One extra step needed")
                .setMessage("Because AWARE wasn't installed from the Play Store, Android blocks " +
                        "Accessibility access until you allow it manually:\n\n" +
                        "1. On the App info screen that opens, tap the ⋮ menu (top-right) and " +
                        "choose \"Allow restricted settings\".\n" +
                        "2. Come back here and tap Enable again to open Accessibility Settings and " +
                        "turn AWARE on.")
                .setPositiveButton("Open App Info", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        shownRestrictedSettingsHelp = true;
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Judge the row from the live permission state (not the raw grantResults array), so a row is
        // marked consented once its required permissions are actually held — tolerating hard-restricted
        // ones the platform won't grant, and not depending on the callback array's exact shape.
        if (requestCode >= 0 && requestCode < items.size()
                && SensorCollection.isAlreadyGranted(getApplicationContext(), items.get(requestCode))) {
            ConsentItem granted = items.get(requestCode);
            consentedKeys.add(granted.key);
            // Location just went from off to on, but the foreground grant alone stops logging whenever
            // AWARE isn't open. Nudge the participant to switch it to "Allow all the time" for complete
            // location data, unless they already have it.
            if ("locations".equals(granted.key)
                    && !SensorCollection.hasBackgroundLocation(getApplicationContext())
                    && !shownAlwaysLocationHelp) {
                promptAlwaysLocation();
            }
        }
        buildRows();
    }

    private void promptAlwaysLocation() {
        shownAlwaysLocationHelp = true;
        new AlertDialog.Builder(this)
                .setTitle("Set location to \"Allow all the time\"")
                .setMessage("To record the places you visit continuously — even when AWARE isn't open — " +
                        "set this app's Location permission to \"Allow all the time\". With only " +
                        "\"While using the app\", location is recorded just while AWARE is open, so the data " +
                        "will be incomplete.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Accessibility grants happen in the Settings app, not this Activity, so there's no
        // onRequestPermissionsResult callback for them — re-check on every return to this screen,
        // attributing the enabled service only to the row whose Enable button was actually tapped.
        if (pendingAccessibilityKey != null && SensorCollection.isAccessibilityServiceEnabled(getApplicationContext())) {
            consentedKeys.add(pendingAccessibilityKey);
            pendingAccessibilityKey = null;
        }
        if (items != null) buildRows();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_CONSENTED_KEYS, new ArrayList<>(consentedKeys));
        outState.putString(STATE_PENDING_ACCESSIBILITY_KEY, pendingAccessibilityKey);
        outState.putBoolean(STATE_SHOWN_RESTRICTED_HELP, shownRestrictedSettingsHelp);
        outState.putBoolean(STATE_SHOWN_ALWAYS_LOCATION_HELP, shownAlwaysLocationHelp);
    }

    private void restoreInstanceState(Bundle state) {
        ArrayList<String> saved = state.getStringArrayList(STATE_CONSENTED_KEYS);
        if (saved != null) consentedKeys.addAll(saved);
        pendingAccessibilityKey = state.getString(STATE_PENDING_ACCESSIBILITY_KEY);
        shownRestrictedSettingsHelp = state.getBoolean(STATE_SHOWN_RESTRICTED_HELP);
        shownAlwaysLocationHelp = state.getBoolean(STATE_SHOWN_ALWAYS_LOCATION_HELP);
    }

    /**
     * Confirm the participant's choices and apply them. Produces the final set of sensors to keep
     * OFF and hands it to {@link ApplySettingsAsync}:
     *   1. begin with the sensors already declined (persisted);
     *   2. for each row shown, drop it from that set if the participant enabled it, or add it if they
     *      left it off;
     *   3. log the enabled/declined lists to the researcher-visible audit trail, then apply.
     * On a fresh join the starting set is empty, so this is simply "everything not enabled is off"
     * (including tapping Continue with nothing enabled). In update mode only the newly-added rows are
     * shown, so earlier declines not on screen are carried through untouched.
     */
    private void onContinue() {
        Set<String> declined = new HashSet<>();
        for (String key : Aware.getSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) declined.add(key.trim());
        }
        for (ConsentItem item : items) {
            List<String> controlled = SensorCollection.controlledSettings(item);
            if (consentedKeys.contains(item.key)) {
                declined.removeAll(controlled);
            } else {
                declined.addAll(controlled);
            }
        }
        Aware.logStudyCompliance(getApplicationContext(),
                (updateMode ? "consent given (study update): " : "consent given: ")
                        + SensorCollection.consentStateSummary(studyConfigs, declined));

        if (preJoin) {
            // Pre-join: record the participant's choices now, before enrolment. applySettings (run
            // later, when they sign up) preserves both across its internal reset and forces the
            // declined sensors off; the config-sync drift check reads the declined set to keep them
            // off. Then hand off to the identifier / sign-up step — do NOT apply or enrol here.
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS,
                    TextUtils.join(",", declined));
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_CONSENT_RECORD,
                    SensorCollection.consentFingerprint(studyUrl, studyConfigs));
            forwardToJoinStudy();
            return;
        }
        applyAndFinish(declined);
    }

    /** Hand off to the identifier / sign-up step after pre-join consent, flagged so it won't re-ask. */
    private void forwardToJoinStudy() {
        Intent join = new Intent(getApplicationContext(), Aware_Join_Study.class);
        join.putExtra(Aware_Join_Study.EXTRA_STUDY_URL, studyUrl);
        try {
            if (studyConfigs.length() > 0) {
                join.putExtra(Aware_Join_Study.EXTRA_STUDY_CONFIG, studyConfigs.getJSONObject(0).toString());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        join.putExtra(Aware_Join_Study.INPUT_PASSWORD, inputPassword);
        join.putExtra(Aware_Join_Study.EXTRA_CONSENT_DONE, true);
        join.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(join);
        finish();
    }

    private void applyAndFinish(Set<String> declinedSettings) {
        // Consent may coexist with a long-running legacy AsyncTask (notably an ESM expiration
        // monitor). Joining must not wait on Android's process-wide serial AsyncTask queue.
        new ApplySettingsAsync(declinedSettings)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void goToMainUi() {
        Intent mainUI = new Intent(getApplicationContext(), Aware_Client.class);
        mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(mainUI);
        finish();
    }

    /** The URL of the study the device is currently enrolled in, or "" if none. */
    private String activeStudyUrl() {
        String url = "";
        Cursor study = Aware.getActiveStudy(getApplicationContext());
        if (study != null && study.moveToFirst()) {
            url = study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL));
        }
        if (study != null && !study.isClosed()) study.close();
        return url;
    }

    /**
     * Leave this screen without agreeing (the "Don't join" / "Not now" button and the Back button).
     * Fresh join: the participant is enrolled but hasn't consented, so undo the enrolment — mark the
     * study row exited, which makes {@link Aware#isStudy} false — and return to the app not enrolled.
     * This is what prevents backing out from leaving a half-joined study the config sync would later
     * turn into a full, unconsented enable. Update mode: the participant is already a member and only
     * skipped the researcher's added sensors, so nothing is rolled back — the added sensors stay off
     * and they go back to the app. Either way the decision is logged to the audit trail.
     */
    private void cancelJoin() {
        if (updateMode) {
            Aware.logStudyCompliance(getApplicationContext(), "study update: added sensors left off");
            goToMainUi();
            return;
        }

        if (preJoin) {
            // Not enrolled yet — enrolment happens at sign-up, after this screen — so there is nothing
            // to roll back. Just leave the flow without joining.
            goToMainUi();
            return;
        }

        Aware.logStudyCompliance(getApplicationContext(), "consent declined — did not join");

        Cursor study = Aware.getStudy(getApplicationContext(), studyUrl);
        if (study != null && study.moveToFirst()) {
            long id = study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_ID));
            ContentValues values = new ContentValues();
            values.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
            getContentResolver().update(Aware_Provider.Aware_Studies.CONTENT_URI, values,
                    Aware_Provider.Aware_Studies.STUDY_ID + "=" + id, null);
        }
        if (study != null && !study.isClosed()) study.close();

        goToMainUi();
    }

    @Override
    public void onBackPressed() {
        cancelJoin();
    }

    private class ApplySettingsAsync extends AsyncTask<Void, Void, Void> {
        private final Set<String> declinedSettings;
        private ProgressDialog mLoading;

        ApplySettingsAsync(Set<String> declinedSettings) {
            this.declinedSettings = declinedSettings;
        }

        @Override
        protected void onPreExecute() {
            if (isFinishing()) return;
            mLoading = new ProgressDialog(SensorConsentActivity.this);
            mLoading.setMessage("Joining study, please wait.");
            mLoading.setCancelable(false);
            mLoading.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Persist the declined set BEFORE applying: applySettings preserves this across its
            // internal reset and forces these sensors off, and the config sync's drift check reads
            // it to keep them off — so the decline survives past the first poll.
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS,
                    TextUtils.join(",", declinedSettings));
            StudyUtils.applySettings(getApplicationContext(), studyUrl, studyConfigs, false, inputPassword, declinedSettings);
            // Record what was agreed to (study + its promptable-sensor set). applySettings preserves
            // it across its internal reset; quitting the study clears it via Aware.reset(), which is
            // what forces the consent screen to show again on a re-join.
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_CONSENT_RECORD,
                    SensorCollection.consentFingerprint(studyUrl, studyConfigs));
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            dismissLoading();
            // The study is applied by this point regardless of this screen's state. Only navigate
            // while this screen is still the current one.
            if (isFinishing()) return;
            Intent mainUI = new Intent(getApplicationContext(), Aware_Client.class);
            mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(mainUI);
            finish();
        }

        /**
         * Dismisses the progress dialog if it is still attached. The dialog belongs to the Activity
         * that started this task, which can be destroyed while the study is being applied;
         * dismissing a window whose Activity is gone throws IllegalArgumentException.
         */
        private void dismissLoading() {
            if (mLoading == null || !mLoading.isShowing()) return;
            try {
                mLoading.dismiss();
            } catch (IllegalArgumentException e) {
                Log.d(Aware.TAG, "Consent loader's window was already gone: " + e.getMessage());
            }
            mLoading = null;
        }
    }
}
