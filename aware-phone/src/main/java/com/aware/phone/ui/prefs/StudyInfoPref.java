package com.aware.phone.ui.prefs;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.aware.Aware;
import com.aware.phone.R;

public class StudyInfoPref extends Preference {

    public StudyInfoPref(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StudyInfoPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StudyInfoPref(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StudyInfoPref(Context context) {
        super(context);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.study_card, parent, false);

        // Use the currently-enrolled study, independent of the WEBSERVICE_SERVER setting
        // (which can drift from the study URL after a reset). See Aware.getActiveStudy().
        Cursor study = Aware.getActiveStudy(getContext());
        if (study != null && study.moveToFirst()) {
            ContentValues row = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(study, row);
            StudyCard.bind(getContext(), view, row);
        }
        if (study != null && !study.isClosed()) study.close();

        return view;
    }
}
