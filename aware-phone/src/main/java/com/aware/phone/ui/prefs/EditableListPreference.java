package com.aware.phone.ui.prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.preference.ListPreference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aware.phone.R;
import com.aware.utils.SensorThresholds;

/**
 * A {@link ListPreference} that offers the curated preset values AND a "Custom…" entry
 * which opens a numeric-input dialog, so a value can either be picked from the recommended
 * options or typed by hand.
 *
 * The optional unit shown next to a custom value (e.g. "seconds") comes from the custom
 * {@code customUnit} XML attribute. It deliberately does not use {@code android:dialogMessage}:
 * legacy preference dialogs render a message instead of their choice list, which would hide every
 * preset and the Custom row.</p>
 */
public class EditableListPreference extends ListPreference {

    private static final String CUSTOM_LABEL = "Custom value…";
    private static final int FORMAT_STORED_VALUE = 0;
    private static final int FORMAT_SAMPLING_RATE_HZ = 1;
    private static final int FORMAT_SENSOR_THRESHOLD = 2;
    private final String customUnit;
    private final int customValueFormat;

    public EditableListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(
                attrs, R.styleable.EditableListPreference);
        customUnit = values.getString(R.styleable.EditableListPreference_customUnit);
        customValueFormat = values.getInt(
                R.styleable.EditableListPreference_customValueFormat, FORMAT_STORED_VALUE);
        values.recycle();
    }

    /**
     * A threshold's unit comes from its setting key rather than the XML, so the unit and the
     * usable range are stated in one place instead of once per sensor in two preference files.
     */
    private CharSequence unit() {
        if (customValueFormat == FORMAT_SENSOR_THRESHOLD) {
            SensorThresholds.Spec spec = SensorThresholds.of(getKey());
            if (spec != null) return spec.unit;
        }
        return customUnit;
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        final CharSequence[] entries = getEntries();
        final CharSequence[] entryValues = getEntryValues();

        // Append a trailing "Custom…" row to the recommended presets.
        final CharSequence[] items = new CharSequence[entries.length + 1];
        System.arraycopy(entries, 0, items, 0, entries.length);
        items[entries.length] = CUSTOM_LABEL;

        int checked = findIndexOfValue(getValue());
        if (checked < 0) checked = entries.length; // current value is a custom one

        final int customIndex = entries.length;
        builder.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (which == customIndex) {
                    showCustomDialog();
                } else {
                    String value = entryValues[which].toString();
                    if (callChangeListener(value)) setValue(value);
                }
            }
        });

        // Tapping a row commits immediately, so no OK button is needed (Cancel stays).
        builder.setPositiveButton(null, null);
    }

    private void showCustomDialog() {
        final EditText input = new EditText(getContext());
        input.setInputType(customValueFormat == FORMAT_STORED_VALUE
                ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (!TextUtils.isEmpty(unit())) input.setHint(unit());
        String current = displayValue(getValue());
        if (!TextUtils.isEmpty(current)) {
            input.setText(current);
            input.setSelection(current.length());
        }

        AlertDialog.Builder customDialog = new AlertDialog.Builder(getContext())
                .setTitle(getDialogTitle() != null ? getDialogTitle() : getTitle());
        if (customValueFormat == FORMAT_SAMPLING_RATE_HZ) {
            customDialog.setView(samplingRateInput(input));
        } else if (customValueFormat == FORMAT_SENSOR_THRESHOLD) {
            customDialog.setView(thresholdInput(input));
        } else {
            customDialog.setView(input);
        }

        customDialog
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String enteredValue = input.getText().toString().trim();
                        String storedValue = storedValue(enteredValue);
                        if (!TextUtils.isEmpty(storedValue) && callChangeListener(storedValue)) {
                            setValue(storedValue);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private LinearLayout samplingRateInput(final EditText input) {
        return annotatedInput(input, "Hz means samples per second.", new Annotation() {
            @Override
            public void describe(TextView target, String value) {
                updateSamplingRateConversion(target, value);
            }
        });
    }

    private LinearLayout thresholdInput(final EditText input) {
        SensorThresholds.Spec spec = SensorThresholds.of(getKey());
        String intro = spec == null
                ? "A reading is stored only when it changed by at least this much."
                : "A reading is stored only when it differs from the last stored reading by at"
                        + " least this much, in " + spec.unit + ".";
        return annotatedInput(input, intro, new Annotation() {
            @Override
            public void describe(TextView target, String value) {
                updateThresholdExplanation(target, value);
            }
        });
    }

    /** The live line under a custom value, restating what the entered number will do. */
    private interface Annotation {
        void describe(TextView target, String value);
    }

    private LinearLayout annotatedInput(final EditText input, String intro,
                                        final Annotation annotation) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = (int) (24 * getContext().getResources()
                .getDisplayMetrics().density);
        content.setPadding(horizontalPadding, 0, horizontalPadding, 0);

        TextView explanation = new TextView(getContext());
        explanation.setText(intro);
        content.addView(explanation);
        content.addView(input);

        final TextView annotated = new TextView(getContext());
        content.addView(annotated);
        annotation.describe(annotated, input.getText().toString());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                annotation.describe(annotated, value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        return content;
    }

    private void updateSamplingRateConversion(TextView conversion, String samplingRateHz) {
        if (TextUtils.isEmpty(samplingRateHz)) {
            conversion.setText("Example: 20 Hz = one sample every 50 ms (1/20 second)");
            return;
        }
        try {
            conversion.setText(FrequencyValueConverter
                    .samplingRateHzDescription(samplingRateHz));
        } catch (IllegalArgumentException ignored) {
            conversion.setText("Enter a sampling rate greater than 0 Hz");
        }
    }

    private void updateThresholdExplanation(TextView explanation, String threshold) {
        if (TextUtils.isEmpty(threshold)) {
            explanation.setText("0 stores every sample, with no filtering.");
            return;
        }
        try {
            explanation.setText(SensorThresholds.explain(getKey(), Double.parseDouble(threshold)));
        } catch (NumberFormatException ignored) {
            explanation.setText("Enter a number.");
        }
    }

    /**
     * The readable label for the current value: the matching preset entry, or a
     * "Custom: <value> <unit>" label when the value isn't one of the presets. Feeds both the
     * {@code %s} summary and the summary refresh done in Aware_Client.
     */
    @Override
    public CharSequence getEntry() {
        CharSequence preset = super.getEntry();
        if (preset != null) return preset;
        String value = getValue();
        if (TextUtils.isEmpty(value)) return null;
        value = displayValue(value);
        if (TextUtils.isEmpty(value)) return null;
        if (customValueFormat == FORMAT_SAMPLING_RATE_HZ) {
            return "Custom: " + value + " " + unit() + " ("
                    + FrequencyValueConverter.samplingRateHzInterval(value) + ")";
        }
        if (customValueFormat == FORMAT_SENSOR_THRESHOLD) {
            // The dialog rejects an out-of-range threshold, but a study config can still push one
            // — and did: a deployed config set 120 m/s². Say so here, because the summary is the
            // only place that value is ever shown.
            return "Custom: " + value + " " + unit()
                    + (isOutOfRange(value) ? " — too high, this sensor records nothing" : "");
        }
        return TextUtils.isEmpty(unit())
                ? "Custom: " + value
                : "Custom: " + value + " " + unit();
    }

    private boolean isOutOfRange(String threshold) {
        try {
            return !SensorThresholds.isWithinRange(getKey(), Double.parseDouble(threshold));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String storedValue(String displayedValue) {
        if (TextUtils.isEmpty(displayedValue)) return null;
        if (customValueFormat == FORMAT_SENSOR_THRESHOLD) {
            // A threshold past the sensor's range filters out every sample, so it is rejected
            // rather than stored: it would leave the sensor reporting itself as enabled while
            // recording nothing, which is indistinguishable from the sensor being broken.
            try {
                double threshold = Double.parseDouble(displayedValue);
                return SensorThresholds.isWithinRange(getKey(), threshold)
                        ? displayedValue
                        : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (customValueFormat != FORMAT_SAMPLING_RATE_HZ) return displayedValue;
        try {
            return FrequencyValueConverter.samplingRateHzToPeriodUs(displayedValue);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String displayValue(String storedValue) {
        if (TextUtils.isEmpty(storedValue)
                || customValueFormat != FORMAT_SAMPLING_RATE_HZ) {
            return storedValue;
        }
        try {
            return FrequencyValueConverter.periodUsToSamplingRateHz(storedValue);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
