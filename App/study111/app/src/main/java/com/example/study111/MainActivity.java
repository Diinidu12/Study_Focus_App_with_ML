package com.example.study111;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * Pomodoro timer w/ Pause/Resume, motion-pause, and Material top app bar.
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // -----------------------------------
    // UI
    // -----------------------------------
    private MaterialToolbar topAppBar;
    private TextView timerText;
    private TextView messageText;
    private TextView resultText;
    private TextView sessionLabel;
    private Button startButton;
    private Button resetButton;

    // -----------------------------------
    // Pomodoro state
    // -----------------------------------
    private enum SessionType { FOCUS, BREAK }
    private SessionType currentSession = SessionType.FOCUS;

    private long focusDurationMs;
    private long breakDurationMs;
    private long timeLeftMs; // countdown remaining

    private CountDownTimer countDownTimer;
    private boolean isTimerRunning = false;     // actively counting down
    private boolean hasStartedSession = false;  // launched at least once since Reset

    // Auto-loop flag
    private static final boolean AUTO_LOOP = false;

    // -----------------------------------
    // Movement detection (Focus only)
    // -----------------------------------
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float lastX, lastY, lastZ;
    private boolean isFirstSensorEvent = true;
    private static final float MOVE_THRESHOLD = 2.0f; // tune

    // -----------------------------------
    // Notifications
    // -----------------------------------
    private static final String CHANNEL_ID = "pomodoro_channel";

    // -----------------------------------
    // Settings launcher
    // -----------------------------------
    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // When returning from settings, apply new durations if safe.
                applyDurationsFromPrefsIfIdle();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);      // layout with MaterialToolbar

        // Bind UI
        topAppBar    = findViewById(R.id.topAppBar);
        timerText    = findViewById(R.id.timerText);
        messageText  = findViewById(R.id.messageText);
        sessionLabel = findViewById(R.id.sessionLabel);
        startButton  = findViewById(R.id.startButton);
        resetButton  = findViewById(R.id.resetButton);
        resultText  = findViewById(R.id.resultText);

        // Toolbar setup
        initToolbar();

        // Sensor
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        createNotificationChannel();

        // Init durations from prefs (fresh launch)
        loadDurationsFromPrefs();
        timeLeftMs = focusDurationMs; // start fresh in Focus state
        updateTimerText();
        updateSessionLabel();
        updateStartButton();

        startButton.setOnClickListener(v -> onStartClicked());
        resetButton.setOnClickListener(v -> onResetClicked());
    }

    private void initToolbar() {
        // If your theme DOES NOT automatically inflate menu via XML, inflate here:
        if (topAppBar.getMenu().size() == 0) {
            topAppBar.inflateMenu(R.menu.top_app_bar_menu);
        }

        topAppBar.setTitle("Focus Timer");

        topAppBar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                openSettings();
                return true;
            }
            return false;
        });
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        settingsLauncher.launch(i);
    }

    // -----------------------------------
    // UI button handlers
    // -----------------------------------
    private void onStartClicked() {
        if (isTimerRunning) {
            // Running -> Pause
            pauseTimer(false);
            messageText.setText("Paused.");
            return;
        }

        // Not running -> either Resume or brand-new Start
        if (!hasStartedSession) {
            // Fresh start: reload prefs & use full duration
            loadDurationsFromPrefs();
            timeLeftMs = (currentSession == SessionType.FOCUS) ? focusDurationMs : breakDurationMs;
            hasStartedSession = true;
        }

        // Resume (or start new) using whatever timeLeftMs currently is
        if (currentSession == SessionType.FOCUS) {
            startFocus();
        } else {
            startBreak();
        }
    }

    private void onResetClicked() {
        cancelTimer();
        currentSession = SessionType.FOCUS;
        hasStartedSession = false;
        loadDurationsFromPrefs();
        timeLeftMs = focusDurationMs;
        updateTimerText();
        updateSessionLabel();
        messageText.setText("Timer reset.");
        updateStartButton();
    }

    // -----------------------------------
    // Session flows
    // -----------------------------------
    private void startFocus() {
        currentSession = SessionType.FOCUS;
        updateSessionLabel();
        messageText.setText("");
        startTimer(timeLeftMs, true /*detectMovement*/);
    }

    private void startBreak() {
        currentSession = SessionType.BREAK;
        updateSessionLabel();
        messageText.setText("Break started.");
        startTimer(timeLeftMs, false /*no movement detection during break*/);
    }

    // -----------------------------------
    // Timer engine
    // -----------------------------------
    private void startTimer(long durationMs, boolean detectMovement) {
        cancelTimer(); // ensure no stale timer
        isTimerRunning = true;
        isFirstSensorEvent = true;

        if (detectMovement && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(this);
        }

        countDownTimer = new CountDownTimer(durationMs, 1000) {
            @Override public void onTick(long millisUntilFinished) {
                timeLeftMs = millisUntilFinished;
                updateTimerText();
            }
            @Override public void onFinish() {
                isTimerRunning = false;
                sensorManager.unregisterListener(MainActivity.this);
                timeLeftMs = 0;
                updateTimerText();
                if (currentSession == SessionType.FOCUS) {
                    notifyUser("Focus complete!");
                    messageText.setText("Focus complete! Break starting…");
                    // Auto-start break
                    timeLeftMs = breakDurationMs;
                    hasStartedSession = true; // new session (break)
                    startBreak();
                } else {
                    notifyUser("Break complete!");
                    messageText.setText("Break complete!");
                    if (AUTO_LOOP) {
                        timeLeftMs = focusDurationMs;
                        hasStartedSession = true; // new focus loop
                        startFocus();
                    } else {
                        currentSession = SessionType.FOCUS;
                        updateSessionLabel();
                        timeLeftMs = focusDurationMs;
                        hasStartedSession = false; // waiting for fresh user start
                        updateTimerText();
                        updateStartButton();
                    }
                }
            }
        }.start();

        updateStartButton();
    }

    /** Cancel the active CountDownTimer if running. Leaves timeLeftMs unchanged. */
    private void cancelTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        isTimerRunning = false;
        sensorManager.unregisterListener(this);
    }

    /** Pause helper used by user action or movement detection. */
    private void pauseTimer(boolean dueToMovement) {
        cancelTimer();
        // Session has been started at least once; keep hasStartedSession = true so we resume.
        hasStartedSession = true;
        if (dueToMovement) {
            notifyUser("Timer paused: phone was moved!");
        }
        updateStartButton();
    }

    // Called when phone moved during Focus
    private void stopDueToMovement() {
        pauseTimer(true);
        messageText.setText("Paused due to movement.");
    }

    // -----------------------------------
    // Sensor callbacks
    // -----------------------------------
    @Override public void onSensorChanged(SensorEvent event) {
        if (!isTimerRunning) return;
        if (currentSession != SessionType.FOCUS) return; // ignore in break
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (isFirstSensorEvent) {
            lastX=x; lastY=y; lastZ=z;
            isFirstSensorEvent=false;
            return;
        }

        float delta = Math.abs(x-lastX) + Math.abs(y-lastY) + Math.abs(z-lastZ);
        if (delta > MOVE_THRESHOLD) {
            stopDueToMovement();
        }

        lastX=x; lastY=y; lastZ=z;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    
    @Override protected void onPause() {
        super.onPause();
        // If timer running and in Focus we should unregister to save power.
        // We'll re-register onResume.
        sensorManager.unregisterListener(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isTimerRunning && currentSession == SessionType.FOCUS && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            // If returning to app (e.g., after settings), try to re-read prefs if idle
            applyDurationsFromPrefsIfIdle();
        }
    }

    // -----------------------------------
    // Duration helpers (Prefs)
    // -----------------------------------
    private void loadDurationsFromPrefs() {
        int fMin = Prefs.getFocusMin(this);
        int bMin = Prefs.getBreakMin(this);
        focusDurationMs = fMin * 60L * 1000L;
        breakDurationMs = bMin * 60L * 1000L;
    }

    /** Apply new prefs only if timer is not currently running and we haven't started current session yet. */
    private void applyDurationsFromPrefsIfIdle() {
        if (isTimerRunning) return; // don't change mid-run
        long prevFocus = focusDurationMs;
        long prevBreak = breakDurationMs;
        loadDurationsFromPrefs();
        if (!hasStartedSession) {
            // safe to update timeLeftMs to new focus duration when idle
            timeLeftMs = (currentSession == SessionType.FOCUS) ? focusDurationMs : breakDurationMs;
            updateTimerText();
            messageText.setText("Settings updated.");
        } else {
            // session already started; don't overwrite remaining time
            // but if user changed settings, show gentle notice after current cycle ends
            if (prevFocus != focusDurationMs || prevBreak != breakDurationMs) {
                messageText.setText("New settings will apply after Reset.");
            }
        }
    }

    // -----------------------------------
    // UI helpers
    // -----------------------------------
    private void updateTimerText() {
        long totalSec = timeLeftMs / 1000;
        int minutes = (int)(totalSec / 60);
        int seconds = (int)(totalSec % 60);
        timerText.setText(String.format("%02d:%02d", minutes, seconds));
    }

    private void updateSessionLabel() {
        sessionLabel.setText(currentSession == SessionType.FOCUS ? "Focus" : "Break");
    }

    /** Update Start button label based on state. */
    private void updateStartButton() {
        if (startButton == null) return;
        if (isTimerRunning) {
            startButton.setText("Pause");
        } else {
            if (hasStartedSession && timeLeftMs > 0) {
                startButton.setText("Resume");
            } else {
                startButton.setText("Start");
            }
        }
    }

    // -----------------------------------
    // Notification / sound / vibration
    // -----------------------------------
    private void notifyUser(String message) {
        playSound();
        vibrate();
        showNotification(message);
    }

    private void playSound() {
        // Raw resource must exist; wrap in try to avoid crash if missing.
        try {
            MediaPlayer mp = MediaPlayer.create(this, R.raw.notification_sound);
            if (mp != null) {
                mp.setOnCompletionListener(MediaPlayer::release);
                mp.start();
            }
        } catch (Exception ignore) {}
    }

    private void vibrate() {
        Vibrator vib = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vib.vibrate(500);
        }
    }

    private void showNotification(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("Pomodoro")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Pomodoro Channel",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Pomodoro Alerts");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }
    private void updateUi(TFLiteClassifier.Result r) {
        if (r == null) return;
        String msg;
        if (r.scores != null && r.scores.length == 2) {
            msg = String.format(Locale.US,
                    "%s (%.3f)\nstationary=%.3f  pick_up=%.3f",
                    r.label, r.confidence, r.scores[0], r.scores[1]);
        } else {
            msg = String.format(Locale.US, "%s (%.3f)", r.label, r.confidence);
        }
        resultText.setText(msg);
    }
}
