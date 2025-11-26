package com.example.study111;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

public class SettingsActivity extends AppCompatActivity {

    private EditText focusInput;
    private EditText breakInput;
    private Button saveButton;
    private Button cancelButton;

    private int origFocus;
    private int origBreak;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        focusInput   = findViewById(R.id.focusMinutesInput);
        breakInput   = findViewById(R.id.breakMinutesInput);
        saveButton   = findViewById(R.id.saveButton);
        cancelButton = findViewById(R.id.cancelButton);

        // Preload current values
        origFocus = Prefs.getFocusMin(this);
        origBreak = Prefs.getBreakMin(this);
        focusInput.setText(String.valueOf(origFocus));
        breakInput.setText(String.valueOf(origBreak));

        // Toolbar back arrow -> behave like Cancel (no save)
        toolbar.setNavigationOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        saveButton.setOnClickListener(v -> {
            int f = parseIntOrDefault(focusInput.getText().toString(), Prefs.DEFAULT_FOCUS_MIN);
            int b = parseIntOrDefault(breakInput.getText().toString(), Prefs.DEFAULT_BREAK_MIN);
            Prefs.setFocusMin(this, f);
            Prefs.setBreakMin(this, b);
            setResult(RESULT_OK);
            finish();
        });

        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private int parseIntOrDefault(String s, int def) {
        if (TextUtils.isEmpty(s)) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

}
