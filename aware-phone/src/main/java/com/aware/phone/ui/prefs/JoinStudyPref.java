package com.aware.phone.ui.prefs;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.aware.phone.R;
import com.aware.phone.ui.dialogs.JoinStudyDialog;

public class JoinStudyPref extends Preference {

    public JoinStudyPref(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public JoinStudyPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public JoinStudyPref(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public JoinStudyPref(Context context) {
        super(context);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.btn_join_study, parent, false);

        view.findViewById(R.id.btn_join_study).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new JoinStudyDialog((Activity) getContext()).showDialog();
            }
        });

        return view;
    }
}
