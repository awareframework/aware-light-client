package com.aware.phone.ui.dialogs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.ScreenShot;
import com.aware.phone.ui.Aware_Client;
import com.aware.providers.Aware_Provider;
import com.aware.utils.StudyUtils;

/**
 * Manages dialog that is used to quit a study.
 */
public class QuitStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::QuitStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;
    private ContentValues mStudyExitEntry;

    public QuitStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle("Leave this study?")
                .setMessage("Leaving stops this study's data collection and removes its settings "
                        + "from this device. Data already uploaded is not deleted.\n\n"
                        + "Are you sure you want to leave?")
                .setCancelable(true)
                .setPositiveButton("Leave study", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getActiveStudy(mActivity);
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            mStudyExitEntry = createStudyExitEntry(dbStudy);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();

                        if (mStudyExitEntry != null) {
                            // Leaving a study is also completion-critical UI work; do not queue it
                            // behind long-lived ESM timers on AsyncTask's serial executor.
                            new QuitStudyAsync().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        } else {
                            showLeaveFailedDialog();
                        }
                    }
                })
                .setNegativeButton("Stay in study", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getActiveStudy(mActivity);
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            ContentValues complianceEntry = new ContentValues();
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(mActivity));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_EXIT)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "canceled quit");

                            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();
                    }
                });
        return builder.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        // A confirmed leave uses the targeted, acknowledged upload below. Starting the full
        // provider sync at the same time would compete for the JDBC connection and make leaving
        // slow again.
        if (mStudyExitEntry != null) return;

        // Sync to server the studies statuses
        Bundle sync = new Bundle();
        sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(Aware.getAWAREAccount(mActivity), Aware_Provider.getAuthority(mActivity), sync);
    }

    /**
     * Store information on attempt to quit study and then show the dialog to confirm the quit.
     */
    public void showDialog() {
        Log.i(TAG, "Quitting from active study");

        Cursor dbStudy = Aware.getActiveStudy(mActivity);
        if (dbStudy != null && dbStudy.moveToFirst()) {
            ContentValues complianceEntry = new ContentValues();
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getDeviceID(mActivity));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_EXIT)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "attempt to quit study");

            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
        }
        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();
        this.show(mActivity.getFragmentManager(), "dialog");
    }

    private class QuitStudyAsync extends AsyncTask<Void, Void, Boolean> {
        ProgressDialog mQuitting;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            mQuitting = new ProgressDialog(mActivity);
            mQuitting.setMessage("Quitting study, please wait.");
            mQuitting.setCancelable(false);
            mQuitting.setInverseBackgroundForced(false);
            mQuitting.show();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            // Best-effort: notify the researcher if the database is reachable, but never block
            // leaving on it. A participant must always be able to withdraw, even when the research
            // database is temporarily down or gone for good. The exit is recorded locally either
            // way; the compliance value records whether the researcher could be notified.
            boolean notified = StudyUtils.uploadStudyExit(
                    mActivity.getApplicationContext(), mStudyExitEntry);
            if (!notified) {
                mStudyExitEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE,
                        "quit study (server unreachable, not notified)");
            }

            mActivity.getContentResolver().insert(
                    Aware_Provider.Aware_Studies.CONTENT_URI, mStudyExitEntry);
            stopScreenshotService();
            Aware.reset(mActivity);
            return notified;
        }


        @Override
        protected void onPostExecute(Boolean notified) {
            super.onPostExecute(notified);
            mQuitting.dismiss();

            // Leaving always succeeds locally; only the researcher notification is best-effort.
            if (!notified && mActivity != null && !mActivity.isFinishing()) {
                Toast.makeText(mActivity,
                        "You've left the study. The researcher could not be notified "
                                + "(server unreachable).",
                        Toast.LENGTH_LONG).show();
            }

            if (mActivity == null) return;
            mActivity.finish();
            Intent mainUI = new Intent(mActivity, Aware_Client.class);
            mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(mainUI);
        }
    }

    private void stopScreenshotService() {
        Intent serviceIntent = new Intent(mActivity, ScreenShot.class);
        mActivity.stopService(serviceIntent);
    }

    private ContentValues createStudyExitEntry(Cursor dbStudy) {
        ContentValues entry = new ContentValues();
        entry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
        entry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID,
                Aware.getDeviceID(mActivity));
        entry.put(Aware_Provider.Aware_Studies.STUDY_KEY,
                dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_API,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_URL,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_PI,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_JOINED,
                dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
        entry.put(Aware_Provider.Aware_Studies.STUDY_TITLE,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "quit study");
        return entry;
    }

    private void showLeaveFailedDialog() {
        if (mActivity == null || mActivity.isFinishing()) return;

        new AlertDialog.Builder(mActivity)
                .setTitle("Could not leave study")
                .setMessage("The researcher could not be notified. Check your internet connection "
                        + "and try again. You are still enrolled and no study settings were removed.")
                .setPositiveButton("OK", null)
                .show();
    }

}
