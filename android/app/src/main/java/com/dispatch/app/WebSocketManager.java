package com.dispatch.app;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocketManager
 *
 * <p>Manages a single OkHttp WebSocket connection to the Dispatch Python server.
 * It abstracts connection lifecycle (connect / disconnect) and message passing
 * so that {@link MainActivity} can focus purely on UI / logic.
 *
 * <p>All callbacks are delivered on OkHttp's background threads; callers
 * should post to the main thread if they need to update the UI.
 */
public class WebSocketManager {

    private static final String TAG = "WebSocketManager";

    // ── Configuration ────────────────────────────────────────────────────────

    /** Timeout for individual read/write/connect operations (seconds). */
    private static final long TIMEOUT_SECONDS = 10;

    // ── State ─────────────────────────────────────────────────────────────────

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private boolean connected = false;

    // ── Listener interface ────────────────────────────────────────────────────

    /**
     * Callback interface for WebSocket events delivered to the owning Activity.
     */
    public interface Listener {
        /** Called when the WebSocket handshake has completed successfully. */
        void onConnected();

        /**
         * Called when a text message is received from the server.
         *
         * @param message Raw JSON string sent by the Python server.
         */
        void onMessageReceived(String message);

        /**
         * Called when the connection has been closed (either cleanly or with an error).
         *
         * @param reason Human-readable explanation of the closure.
         */
        void onDisconnected(String reason);

        /**
         * Called when an unrecoverable error occurs on the WebSocket.
         *
         * @param errorMessage Description of the error.
         */
        void onError(String errorMessage);
    }

    private final Listener listener;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param listener The object that will receive all WebSocket events.
     */
    public WebSocketManager(@NonNull Listener listener) {
        this.listener = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens a WebSocket connection to the given URL.
     *
     * <p>If a connection is already open it is closed first.
     *
     * @param serverUrl Full WebSocket URL, e.g. {@code ws://192.168.1.10:8765}.
     */
    public void connect(@NonNull String serverUrl) {
        // Close any existing connection gracefully before creating a new one.
        disconnect();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(serverUrl)
                .build();

        webSocket = httpClient.newWebSocket(request, new InternalListener());
        Log.i(TAG, "Connecting to " + serverUrl);
    }

    /**
     * Sends a JSON command to the Python server.
     *
     * <p>The expected payload structure is:
     * <pre>
     * {
     *   "type":      "command",
     *   "window_id": &lt;int&gt;,
     *   "text":      "&lt;transcribed voice text&gt;"
     * }
     * </pre>
     *
     * @param windowId  ID of the target terminal window on the desktop.
     * @param text      Transcribed voice command to inject.
     * @return {@code true} if the message was queued for delivery.
     */
    public boolean sendCommand(int windowId, @NonNull String text) {
        if (!connected || webSocket == null) {
            Log.w(TAG, "sendCommand called but WebSocket is not connected.");
            return false;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", "command");
            payload.put("window_id", windowId);
            payload.put("text", text);
            boolean sent = webSocket.send(payload.toString());
            Log.d(TAG, "sendCommand → window=" + windowId + " text=\"" + text + "\" sent=" + sent);
            return sent;
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build JSON command", e);
            return false;
        }
    }

    /**
     * Closes the WebSocket connection and releases the underlying HTTP client.
     */
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnecting");
            webSocket = null;
        }
        if (httpClient != null) {
            // Shut down the dispatcher's executor so OkHttp doesn't keep threads alive.
            httpClient.dispatcher().executorService().shutdown();
            httpClient = null;
        }
        connected = false;
    }

    /**
     * @return {@code true} if the WebSocket is currently open.
     */
    public boolean isConnected() {
        return connected;
    }

    // ── Internal OkHttp listener ──────────────────────────────────────────────

    private class InternalListener extends WebSocketListener {

        @Override
        public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
            connected = true;
            Log.i(TAG, "WebSocket opened: " + response.message());
            listener.onConnected();
        }

        @Override
        public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
            Log.d(TAG, "Message received: " + text);
            listener.onMessageReceived(text);
        }

        @Override
        public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
            ws.close(1000, null);
            Log.i(TAG, "WebSocket closing: [" + code + "] " + reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
            connected = false;
            Log.i(TAG, "WebSocket closed: [" + code + "] " + reason);
            listener.onDisconnected("Connection closed: " + reason);
        }

        @Override
        public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t,
                              Response response) {
            connected = false;
            String msg = t.getMessage() != null ? t.getMessage() : "Unknown error";
            Log.e(TAG, "WebSocket failure: " + msg, t);
            listener.onError(msg);
        }
    }
}
