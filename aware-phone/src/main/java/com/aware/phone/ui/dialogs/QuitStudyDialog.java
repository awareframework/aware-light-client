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

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.ui.Aware_Light_Client;
import com.aware.providers.Aware_Provider;


/**
 * Manages dialog that is used to quit a study.
 */
public class QuitStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::QuitStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;

    public QuitStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle("Quit Study")
                .setMessage("Are you sure you want to quit the study?")
                .setCancelable(true)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getStudy(mActivity, Aware.getSetting(mActivity, Aware_Preferences.WEBSERVICE_SERVER));
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            ContentValues complianceEntry = new ContentValues();
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "quit study");

                            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();

                        new QuitStudyAsync().execute();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getStudy(mActivity, Aware.getSetting(mActivity, Aware_Preferences.WEBSERVICE_SERVER));
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            ContentValues complianceEntry = new ContentValues();
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
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
        String study_url = Aware.getSetting(mActivity, Aware_Preferences.WEBSERVICE_SERVER);
        Log.i(TAG, "Quitting from study with URL: " + study_url);

        Cursor dbStudy = Aware.getStudy(mActivity, study_url);
        if (dbStudy != null && dbStudy.moveToFirst()) {
            ContentValues complianceEntry = new ContentValues();
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
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

    private class QuitStudyAsync extends AsyncTask<Void, Void, Void> {
        ProgressDialog mQuitting;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            mQuitting = new ProgressDialog(mActivity);
            mQuitting.setMessage("Quitting study, please wait.");
            mQuitting.setCancelable(false);
            mQuitting.setInverseBackgroundForced(false);
            mQuitting.show();
            mQuitting.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    mActivity.finish();

                    // Redirect the user to the main UI
                    Intent mainUI = new Intent(mActivity, Aware_Light_Client.class);
                    mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(mainUI);
                }
            });
        }

        @Override
        protected Void doInBackground(Void... params) {
            Aware.reset(mActivity);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            mQuitting.dismiss();
        }
    }
}
