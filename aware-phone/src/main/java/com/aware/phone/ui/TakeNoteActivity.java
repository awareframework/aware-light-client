package com.aware.phone.ui;

import android.annotation.SuppressLint;
import android.app.Activity;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;

import android.text.TextWatcher;
import android.view.View;

import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.Notes;
import com.aware.phone.R;
import com.aware.providers.Notes_Provider;


public class TakeNoteActivity extends AppCompatActivity {
    private EditText noteEditText;
    private TextView charCountText;
    private final int MAX_CHARS = 10000;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_take_note);

        noteEditText = findViewById(R.id.note_edit_text);
        charCountText = findViewById(R.id.char_count_text);

        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String noteContent = noteEditText.getText().toString();
                if (!TextUtils.isEmpty(noteContent)) {
                    // Create result intent
                    saveNote();
                }
            }
        });

        findViewById(R.id.cancel_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        });

        charCountText.setText("0/" + MAX_CHARS);

        // Add TextWatcher to monitor text changes
        noteEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                charCountText.setText(charSequence.length() + "/" + MAX_CHARS);
            }

            @Override
            public void afterTextChanged(Editable editable) {}
    });



    }

    private void saveNote() {
        String noteText = noteEditText.getText().toString().trim();
        if (noteText.isEmpty()) {
            Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show();
            return;
        }
        // Prepare the note data
        ContentValues values = new ContentValues();
        values.put("timestamp", System.currentTimeMillis());
        values.put("device_id", Aware.getDeviceID(this));
        values.put("note", noteText);

        // Insert the note using the content provider
        getContentResolver().insert(Notes_Provider.Notes_Data.CONTENT_URI, values);

        // Broadcast that a new note was added
        Intent noteIntent = new Intent(Notes.ACTION_AWARE_NOTES);
        sendBroadcast(noteIntent);

        Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}

