package com.aware.phone.ui.prefs;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.aware.phone.R;
import com.aware.phone.ui.dialogs.QuitStudyDialog;

public class QuitStudyPref extends Preference {

    public QuitStudyPref(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public QuitStudyPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public QuitStudyPref(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QuitStudyPref(Context context) {
        super(context);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.btn_quit_study, parent, false);

        view.findViewById(R.id.btn_quit_study).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QuitStudyDialog((Activity) getContext()).showDialog();
            }
        });

        return view;
    }
}
