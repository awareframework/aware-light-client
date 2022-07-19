package com.aware.phone.ui.prefs;

import android.content.Context;
import android.database.Cursor;
import android.preference.Preference;
import android.text.Html;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.providers.Aware_Provider;

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
        View view = inflater.inflate(R.layout.pref_study_info, parent, false);

        TextView tvStudyName = view.findViewById(R.id.study_name);
        TextView tvStudyDesc = view.findViewById(R.id.study_description);
        TextView tvStudyContact = view.findViewById(R.id.study_contact);

        Cursor study = Aware.getStudy(getContext(),
                Aware.getSetting(getContext(), Aware_Preferences.WEBSERVICE_SERVER));
        if (study != null && study.moveToFirst()) {
            tvStudyName.setText(study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
            tvStudyDesc.setText(Html.fromHtml(study.getString(study.getColumnIndex(
                    Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)), null, null));
            tvStudyContact.setText(study.getString(study.getColumnIndex(
                    Aware_Provider.Aware_Studies.STUDY_PI)));
        }

        return view;
    }
}
