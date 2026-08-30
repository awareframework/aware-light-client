package com.aware.phone.ui.prefs;

import android.content.ContentValues;
import android.content.Context;
import android.text.Html;
import android.util.Patterns;
import android.view.View;
import android.widget.TextView;

import com.aware.phone.R;
import com.aware.phone.utils.AwareUtil;
import com.aware.providers.Aware_Provider;

import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;

/**
 * Binds the shared {@code R.layout.study_card} view from a study row. Used by both the current
 * study (StudyInfoPref, study mode) and the joined-studies history (device mode) so the two
 * always render consistently. Empty fields hide their row; the Email and Study link rows are
 * tap-to-copy.
 */
public final class StudyCard {

    private StudyCard() {}

    /**
     * Populates a {@code study_card} view from a study row's columns.
     *
     * @param context used for clipboard + toast on the copyable rows
     * @param card    an inflated {@code R.layout.study_card}
     * @param study   study columns (see {@link Aware_Provider.Aware_Studies})
     */
    public static void bind(final Context context, View card, ContentValues study) {
        TextView name = card.findViewById(R.id.study_name);
        name.setText(study.getAsString(Aware_Provider.Aware_Studies.STUDY_TITLE));

        TextView description = card.findViewById(R.id.study_description);
        String desc = study.getAsString(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION);
        if (desc != null && desc.trim().length() > 0) {
            description.setText(Html.fromHtml(desc, null, null));
            description.setVisibility(View.VISIBLE);
        } else {
            description.setVisibility(View.GONE);
        }

        // STUDY_PI is stored as "First Last\nContact: email@x.com": name under Contact, email
        // in its own tappable (blue) row.
        String pi = study.getAsString(Aware_Provider.Aware_Studies.STUDY_PI);
        setRow(card, R.id.row_contact, R.id.study_contact, extractName(pi));

        final String email = extractEmail(pi);
        setRow(card, R.id.row_email, R.id.study_email, email);
        bindCopy(context, card, R.id.study_email, "AWARE study contact", email);

        final String url = study.getAsString(Aware_Provider.Aware_Studies.STUDY_URL);
        setRow(card, R.id.row_link, R.id.study_link, url);
        bindCopy(context, card, R.id.study_link, "AWARE study link", url);

        Double joined = study.getAsDouble(Aware_Provider.Aware_Studies.STUDY_JOINED);
        long joinedMs = joined != null ? joined.longValue() : 0;
        setRow(card, R.id.row_joined, R.id.study_joined,
                joinedMs > 0 ? DateFormat.getDateInstance().format(new Date(joinedMs)) : null);

        Double exit = study.getAsDouble(Aware_Provider.Aware_Studies.STUDY_EXIT);
        boolean enrolled = exit == null || exit == 0;
        setRow(card, R.id.row_status, R.id.study_status, enrolled ? "Enrolled" : "Left");
    }

    /** Wires a value view to copy the given text to the clipboard on tap (no-op if empty). */
    private static void bindCopy(final Context context, View card, int valueId,
                                 final String label, final String text) {
        TextView value = card.findViewById(valueId);
        if (text != null && text.length() > 0) {
            value.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AwareUtil.copyToClipboard(context, label, text);
                }
            });
        }
    }

    /** Sets a labeled row's value, hiding the whole row when the value is empty. */
    private static void setRow(View card, int rowId, int valueId, String value) {
        View row = card.findViewById(rowId);
        TextView valueView = card.findViewById(valueId);
        if (value == null || value.trim().length() == 0) {
            if (row != null) row.setVisibility(View.GONE);
        } else {
            if (row != null) row.setVisibility(View.VISIBLE);
            valueView.setText(value);
        }
    }

    /**
     * Researcher name from a contact/PI string: first line with any "Contact:" label and email
     * removed. Null if nothing meaningful remains.
     */
    static String extractName(String contact) {
        if (contact == null) return null;
        String name = contact;
        int newline = name.indexOf('\n');
        if (newline >= 0) name = name.substring(0, newline);
        name = name.replace("Contact:", "");
        String email = extractEmail(name);
        if (email != null) name = name.replace(email, "");
        name = name.trim();
        return name.length() > 0 ? name : null;
    }

    /** Email address found in a contact/PI string, or null. */
    static String extractEmail(String contact) {
        if (contact == null) return null;
        Matcher matcher = Patterns.EMAIL_ADDRESS.matcher(contact);
        return matcher.find() ? matcher.group() : null;
    }
}
