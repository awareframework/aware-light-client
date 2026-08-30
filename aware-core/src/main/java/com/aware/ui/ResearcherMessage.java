package com.aware.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.aware.R;

/**
 * One message from the researcher, shown as a dialog the participant dismisses.
 *
 * Nothing is asked here and nothing is recorded: a notice exists to say something,
 * and the client keeps no row for it. It carries its own words in the intent rather
 * than reading them back from anywhere, because the notification that opened it is
 * the only copy the phone has --- which also means dismissing it is the end of it.
 *
 * Opened from the notification, so tapping shows the message rather than the app's
 * main screen, where a participant would have to work out what they were told.
 */
public class ResearcherMessage extends AppCompatActivity {

    public static final String EXTRA_TITLE = "researcher_message_title";
    public static final String EXTRA_BODY = "researcher_message_body";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        String body = intent != null ? intent.getStringExtra(EXTRA_BODY) : null;

        if (title == null || title.trim().length() == 0)
            title = getString(R.string.aware_notif_researcher_message_title);
        if (body == null || body.trim().length() == 0)
            body = getString(R.string.aware_notif_researcher_message_body);

        // Anonymous classes rather than lambdas: this module is dexed without
        // desugaring, so Java 8 bytecode here fails the build rather than this file.
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A second message arriving while this one is up replaces it, rather than
        // stacking a dialog the participant has to dismiss twice.
        setIntent(intent);
        recreate();
    }
}
