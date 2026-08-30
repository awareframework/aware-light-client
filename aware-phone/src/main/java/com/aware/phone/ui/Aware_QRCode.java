package com.aware.phone.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.aware.Aware;
import com.aware.phone.ui.dialogs.JoinStudyDialog;
import com.aware.utils.LogRedactor;

import me.dm7.barcodescanner.zbar.Result;
import me.dm7.barcodescanner.zbar.ZBarScannerView;

/**
 * Reads a study link from a QR code and hands it to the join flow.
 *
 * The scan is one of two ways into the same journey: {@link JoinStudyDialog} fetches
 * the study configuration from the link, checks it against the dataflow the study
 * declares, and shows the consent screen. Pasting a link and scanning one therefore
 * reach a participant through one path, and a study that joins by pasting joins by
 * scanning too.
 */
public class Aware_QRCode extends Aware_Activity implements ZBarScannerView.ResultHandler {

    private ZBarScannerView mScannerView;

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mScannerView = new ZBarScannerView(this);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        ListView list = new ListView(this);
        list.setId(android.R.id.list);
        list.setVisibility(View.GONE);
        main.addView(mScannerView);
        main.addView(list);
        setContentView(main);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mScannerView.setResultHandler(this);
        mScannerView.startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mScannerView.stopCamera();
        mScannerView.stopCameraPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    //Zbar QRCode handler
    @Override
    public void handleResult(Result result) {
        String studyUrl = result.getContents();
        Log.d(Aware.TAG, "QR Code result: " + LogRedactor.redact(studyUrl));

        // Validation reports its own outcome to the participant: the consent screen on
        // success, a toast naming what was wrong otherwise. The preview runs again so a
        // link that turned out to be the wrong one can be followed by another scan.
        new JoinStudyDialog(this).validateStudy(studyUrl);
        mScannerView.resumeCameraPreview(this);
    }
}
