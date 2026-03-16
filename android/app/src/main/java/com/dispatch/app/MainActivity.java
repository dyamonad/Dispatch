package com.dispatch.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

/**
 * MainActivity
 *
 * <p>Entry point of the Dispatch Android app.  Responsibilities:
 * <ol>
 *   <li>Connect to the Python WebSocket server.</li>
 *   <li>Receive JSON window descriptors and forward them to {@link DesktopWindowView}.</li>
 *   <li>Intercept hardware Volume buttons <em>without</em> changing media volume:
 *       <ul>
 *         <li>Volume UP (click) → cycle selection in {@link DesktopWindowView}.</li>
 *         <li>Volume DOWN (hold) → turn selected window red and start
 *             {@link SpeechRecognizer}.</li>
 *         <li>Volume DOWN (release) → stop recording, collect text, send via
 *             WebSocket.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
public class MainActivity extends AppCompatActivity
        implements WebSocketManager.Listener {

    private static final String TAG = "MainActivity";
    private static final int    REQUEST_RECORD_AUDIO = 101;

    // ── UI references ─────────────────────────────────────────────────────────
    private EditText          editServerIp;
    private Button            btnConnect;
    private DesktopWindowView desktopWindowView;
    private TextView          tvStatus;

    // ── Managers ──────────────────────────────────────────────────────────────
    private WebSocketManager  wsManager;

    // ── Speech recognition ────────────────────────────────────────────────────
    private SpeechRecognizer  speechRecognizer;
    private boolean           isRecognizing = false;

    // ── Volume-down state guard ────────────────────────────────────────────────
    /**
     * Tracks whether Volume-Down was the key that initiated the current
     * recording session.  Prevents spurious ACTION_UP events (e.g. from
     * other key sources) from finalising a recording.
     */
    private boolean volDownHeld = false;

    // ── Main-thread handler (for callbacks from background threads) ───────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views.
        editServerIp    = findViewById(R.id.editServerIp);
        btnConnect      = findViewById(R.id.btnConnect);
        desktopWindowView = findViewById(R.id.desktopWindowView);
        tvStatus        = findViewById(R.id.tvStatus);

        // Initialise WebSocket manager.
        wsManager = new WebSocketManager(this);

        // Connect / Disconnect button.
        btnConnect.setOnClickListener(v -> onConnectClicked());

        // Request microphone permission if not already granted.
        ensureMicrophonePermission();

        // Build the SpeechRecognizer once (will be reused per recording).
        initSpeechRecognizer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wsManager != null) wsManager.disconnect();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection logic
    // ─────────────────────────────────────────────────────────────────────────

    private void onConnectClicked() {
        if (wsManager.isConnected()) {
            wsManager.disconnect();
            updateStatus(getString(R.string.status_disconnected),
                    getResources().getColor(R.color.status_disconnected, getTheme()));
            btnConnect.setText(R.string.btn_connect);
        } else {
            String url = editServerIp.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a server URL.", Toast.LENGTH_SHORT).show();
                return;
            }
            updateStatus(getString(R.string.status_connecting),
                    getResources().getColor(R.color.text_hint, getTheme()));
            wsManager.connect(url);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocketManager.Listener callbacks  (delivered on OkHttp background thread)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onConnected() {
        mainHandler.post(() -> {
            updateStatus(getString(R.string.status_connected),
                    getResources().getColor(R.color.status_connected, getTheme()));
            btnConnect.setText(R.string.btn_disconnect);
            Toast.makeText(this, "Connected to server.", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onMessageReceived(String message) {
        Log.d(TAG, "Raw message: " + message);

        // All messages from the server arrive as a JSON string.
        // Determine message type and dispatch accordingly.
        mainHandler.post(() -> handleServerMessage(message));
    }

    @Override
    public void onDisconnected(String reason) {
        mainHandler.post(() -> {
            updateStatus(getString(R.string.status_disconnected),
                    getResources().getColor(R.color.status_disconnected, getTheme()));
            btnConnect.setText(R.string.btn_connect);
            Log.i(TAG, "Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String errorMessage) {
        mainHandler.post(() -> {
            updateStatus("Error: " + errorMessage,
                    getResources().getColor(R.color.status_disconnected, getTheme()));
            btnConnect.setText(R.string.btn_connect);
            Toast.makeText(this, "WebSocket error: " + errorMessage, Toast.LENGTH_LONG).show();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server message handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Routes an incoming server message based on its JSON "type" field.
     *
     * <p>Expected message types:
     * <ul>
     *   <li>{@code "windows"} – payload contains a {@code "data"} JSON array of
     *       window descriptors to forward to {@link DesktopWindowView}.</li>
     * </ul>
     *
     * @param rawJson Raw JSON string from the server.
     */
    private void handleServerMessage(String rawJson) {
        try {
            // The server may send either a typed wrapper object or a bare JSON array.
            if (rawJson.trim().startsWith("[")) {
                // Bare array → treat directly as window list.
                desktopWindowView.updateWindows(rawJson);
            } else {
                org.json.JSONObject obj = new org.json.JSONObject(rawJson);
                String type = obj.optString("type", "");
                switch (type) {
                    case "windows":
                        JSONArray data = obj.getJSONArray("data");
                        desktopWindowView.updateWindows(data.toString());
                        break;
                    default:
                        Log.w(TAG, "Unknown message type: " + type);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse server message: " + rawJson, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hardware Volume-button interception
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Intercepts key events <em>before</em> they are dispatched to child views.
     * By consuming Volume Up/Down events here we prevent the system from
     * changing the actual media volume.
     *
     * @param event The key event.
     * @return {@code true} if the event was consumed, {@code false} otherwise.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                handleVolumeUp();
            }
            return true; // Consume – do NOT change media volume.
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !volDownHeld) {
                volDownHeld = true;
                handleVolumeDownPress();
            } else if (event.getAction() == KeyEvent.ACTION_UP && volDownHeld) {
                volDownHeld = false;
                handleVolumeDownRelease();
            }
            return true; // Consume – do NOT change media volume.
        }

        return super.dispatchKeyEvent(event);
    }

    // ── Volume button handlers ────────────────────────────────────────────────

    /**
     * Volume UP (click): cycle focus/highlight to the next window in the canvas.
     */
    private void handleVolumeUp() {
        DesktopWindowView.WindowInfo next = desktopWindowView.selectNext();
        if (next != null) {
            Log.d(TAG, "Selected window #" + next.id + ": " + next.title);
        }
    }

    /**
     * Volume DOWN (press): highlight selected window red and start recording.
     */
    private void handleVolumeDownPress() {
        if (!wsManager.isConnected()) {
            Toast.makeText(this, "Not connected to server.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (desktopWindowView.getWindowCount() == 0) {
            Toast.makeText(this, "No windows to target.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Flip the view to recording (red) state.
        desktopWindowView.setRecording(true);
        updateStatus(getString(R.string.status_recording),
                getResources().getColor(R.color.status_recording, getTheme()));

        // Begin speech recognition.
        startListening();
    }

    /**
     * Volume DOWN (release): stop recording and send the transcribed text.
     *
     * <p>The actual text will be delivered asynchronously via
     * {@link RecognitionListener#onResults}, so we just stop the recogniser
     * here; the result handler does the sending.
     */
    private void handleVolumeDownRelease() {
        stopListening();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Speech recognition
    // ─────────────────────────────────────────────────────────────────────────

    /** Creates the {@link SpeechRecognizer} and attaches the result listener. */
    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer is not available on this device.");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "SpeechRecognizer: ready for speech");
                isRecognizing = true;
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: speech started");
            }

            @Override
            public void onRmsChanged(float rmsdB) { /* ignored */ }

            @Override
            public void onBufferReceived(byte[] buffer) { /* ignored */ }

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: speech ended");
                isRecognizing = false;
            }

            @Override
            public void onError(int error) {
                isRecognizing = false;
                desktopWindowView.setRecording(false);
                updateStatus(getString(R.string.status_connected),
                        getResources().getColor(R.color.status_connected, getTheme()));
                Log.e(TAG, "SpeechRecognizer error: " + error);
                String msg = speechErrorToString(error);
                Toast.makeText(MainActivity.this, "Speech error: " + msg,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                isRecognizing = false;
                desktopWindowView.setRecording(false);

                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    Log.i(TAG, "Recognised text: \"" + text + "\"");
                    sendVoiceCommand(text);
                } else {
                    Log.w(TAG, "No speech recognised.");
                    updateStatus(getString(R.string.status_connected),
                            getResources().getColor(R.color.status_connected, getTheme()));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) { /* ignored */ }

            @Override
            public void onEvent(int eventType, Bundle params) { /* ignored */ }
        });
    }

    /** Starts the speech recogniser in offline-preferred, single-shot mode. */
    private void startListening() {
        if (speechRecognizer == null) {
            Toast.makeText(this, "Speech recognizer unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isRecognizing) return; // Guard against double-start.

        // Check permission before each start (user may revoke at any time).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ensureMicrophonePermission();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        speechRecognizer.startListening(intent);
    }

    /** Stops the speech recogniser and triggers result delivery. */
    private void stopListening() {
        if (speechRecognizer != null && isRecognizing) {
            speechRecognizer.stopListening();
        }
        // If recognizer was never ready the recording flag must still be cleared.
        if (!isRecognizing) {
            desktopWindowView.setRecording(false);
            if (wsManager.isConnected()) {
                updateStatus(getString(R.string.status_connected),
                        getResources().getColor(R.color.status_connected, getTheme()));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sending commands
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends the recognised text to the Python server, targeting the currently
     * selected window.
     *
     * @param text Transcribed voice command.
     */
    private void sendVoiceCommand(String text) {
        DesktopWindowView.WindowInfo selected = desktopWindowView.getSelectedWindow();
        if (selected == null) {
            Log.w(TAG, "sendVoiceCommand: no window selected.");
            return;
        }

        updateStatus(getString(R.string.status_sending),
                getResources().getColor(R.color.text_hint, getTheme()));

        boolean sent = wsManager.sendCommand(selected.id, text);

        if (sent) {
            Toast.makeText(this,
                    "Sent to #" + selected.id + ": " + text,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to send command.", Toast.LENGTH_SHORT).show();
        }

        updateStatus(getString(R.string.status_connected),
                getResources().getColor(R.color.status_connected, getTheme()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private void ensureMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Microphone permission granted.");
            } else {
                Toast.makeText(this, getString(R.string.permission_rationale),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Posts a status-bar update safely to the main thread. */
    private void updateStatus(String text, int color) {
        mainHandler.post(() -> {
            tvStatus.setText(text);
            tvStatus.setTextColor(color);
        });
    }

    /** Converts a {@link SpeechRecognizer} error code into a human-readable string. */
    private static String speechErrorToString(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:            return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:           return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:          return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:  return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:         return "No speech match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:  return "Recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:           return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:   return "Speech timeout";
            default:                                      return "Unknown error (" + errorCode + ")";
        }
    }
}
