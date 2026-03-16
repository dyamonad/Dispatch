package com.dispatch.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * DesktopWindowView
 *
 * <p>A custom {@link View} that receives a JSON array of desktop terminal-window
 * descriptors from the Python WebSocket server and draws proportional coloured
 * rectangles representing those windows on the phone screen.
 *
 * <p>Expected JSON format (array of window objects):
 * <pre>
 * [
 *   { "id": 0, "title": "Terminal", "x": 0, "y": 0, "width": 800, "height": 600 },
 *   ...
 * ]
 * </pre>
 *
 * <p>Visual states:
 * <ul>
 *   <li>Idle (not selected) → blue fill</li>
 *   <li>Selected (focused) → green fill</li>
 *   <li>Selected + recording → red fill</li>
 * </ul>
 */
public class DesktopWindowView extends View {

    private static final String TAG = "DesktopWindowView";

    // ── Internal model ────────────────────────────────────────────────────────

    /** Represents one terminal window as reported by the desktop server. */
    public static class WindowInfo {
        public final int id;
        public final String title;
        /** Desktop pixel coordinates & size. */
        public final float desktopX, desktopY, desktopW, desktopH;

        public WindowInfo(int id, String title,
                          float desktopX, float desktopY,
                          float desktopW, float desktopH) {
            this.id = id;
            this.title = title;
            this.desktopX = desktopX;
            this.desktopY = desktopY;
            this.desktopW = desktopW;
            this.desktopH = desktopH;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<WindowInfo> windows = new ArrayList<>();

    /** Index into {@link #windows} for the currently selected window. */
    private int selectedIndex = 0;

    /** When {@code true}, the selected window is shown in "recording" red. */
    private boolean isRecording = false;

    /** Virtual desktop bounding box (derived from all window geometries). */
    private float desktopMaxX = 1920f;
    private float desktopMaxY = 1080f;

    // ── Paint objects (created once, reused for every draw pass) ──────────────

    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Colours ───────────────────────────────────────────────────────────────

    private static final int COLOR_IDLE      = 0xFF2196F3; // blue
    private static final int COLOR_SELECTED  = 0xFF4CAF50; // green
    private static final int COLOR_RECORDING = 0xFFF44336; // red
    private static final int COLOR_BORDER    = 0xFFFFFFFF;
    private static final int COLOR_LABEL     = 0xFFFFFFFF;
    private static final int COLOR_EMPTY     = 0xFF888888;

    // Padding around the drawing area (dp converted in init)
    private float padding;

    // ── Constructors ──────────────────────────────────────────────────────────

    public DesktopWindowView(Context context) {
        super(context);
        init();
    }

    public DesktopWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DesktopWindowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        padding = 16f * density;

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * density);
        borderPaint.setColor(COLOR_BORDER);

        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(12f * density);

        fillPaint.setStyle(Paint.Style.FILL);

        emptyPaint.setStyle(Paint.Style.FILL);
        emptyPaint.setColor(COLOR_EMPTY);
        emptyPaint.setTextSize(14f * density);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parses the JSON array of window descriptors received from the server and
     * triggers a redraw.
     *
     * @param json Raw JSON string (array) from the Python server.
     */
    public void updateWindows(@NonNull String json) {
        List<WindowInfo> parsed = new ArrayList<>();
        float maxX = 0, maxY = 0;

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                int    id    = obj.getInt("id");
                String title = obj.optString("title", "Window " + id);
                float  x     = (float) obj.getDouble("x");
                float  y     = (float) obj.getDouble("y");
                float  w     = (float) obj.getDouble("width");
                float  h     = (float) obj.getDouble("height");

                parsed.add(new WindowInfo(id, title, x, y, w, h));

                if (x + w > maxX) maxX = x + w;
                if (y + h > maxY) maxY = y + h;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse window JSON", e);
        }

        windows.clear();
        windows.addAll(parsed);

        // Update virtual desktop dimensions (use at least 1920×1080 as fallback).
        desktopMaxX = Math.max(maxX, 1920f);
        desktopMaxY = Math.max(maxY, 1080f);

        // Reset selection if it's now out of bounds.
        if (selectedIndex >= windows.size()) {
            selectedIndex = 0;
        }

        // Request a redraw on the main thread.
        postInvalidate();
    }

    /**
     * Cycles the selection to the next window (Volume Up handler).
     *
     * @return The newly selected {@link WindowInfo}, or {@code null} if no windows.
     */
    public WindowInfo selectNext() {
        if (windows.isEmpty()) return null;
        selectedIndex = (selectedIndex + 1) % windows.size();
        invalidate();
        return windows.get(selectedIndex);
    }

    /**
     * Marks the view as "recording" (Volume Down pressed) and redraws.
     *
     * @param recording {@code true} to show the red recording state.
     */
    public void setRecording(boolean recording) {
        isRecording = recording;
        invalidate();
    }

    /**
     * @return The currently selected {@link WindowInfo}, or {@code null}.
     */
    public WindowInfo getSelectedWindow() {
        if (windows.isEmpty()) return null;
        return windows.get(selectedIndex);
    }

    /**
     * @return Number of windows currently displayed.
     */
    public int getWindowCount() {
        return windows.size();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        // Available drawing area (excluding padding).
        float areaW = getWidth()  - padding * 2;
        float areaH = getHeight() - padding * 2;

        if (windows.isEmpty()) {
            // Show a hint when no windows have been received yet.
            canvas.drawText(
                    "No terminal windows detected",
                    getWidth() / 2f,
                    getHeight() / 2f,
                    emptyPaint);
            return;
        }

        // Scale factors to map desktop pixel space → view pixel space.
        float scaleX = areaW / desktopMaxX;
        float scaleY = areaH / desktopMaxY;

        for (int i = 0; i < windows.size(); i++) {
            WindowInfo win = windows.get(i);

            // Map desktop coordinates to view coordinates.
            float left   = padding + win.desktopX * scaleX;
            float top    = padding + win.desktopY * scaleY;
            float right  = left + win.desktopW * scaleX;
            float bottom = top  + win.desktopH * scaleY;

            RectF rect = new RectF(left, top, right, bottom);

            // Choose fill colour based on state.
            if (i == selectedIndex) {
                fillPaint.setColor(isRecording ? COLOR_RECORDING : COLOR_SELECTED);
                fillPaint.setAlpha(200);
            } else {
                fillPaint.setColor(COLOR_IDLE);
                fillPaint.setAlpha(140);
            }

            // Draw filled rectangle.
            canvas.drawRoundRect(rect, 6f, 6f, fillPaint);

            // Draw border.
            borderPaint.setAlpha(i == selectedIndex ? 255 : 180);
            canvas.drawRoundRect(rect, 6f, 6f, borderPaint);

            // Draw window label (ID + title), clipped to the rectangle.
            String label = "#" + win.id + "  " + win.title;
            float labelX = left + 6f;
            float labelY = top  + labelPaint.getTextSize() + 4f;
            // Only draw label if it fits horizontally.
            if (labelX < right - 4f) {
                canvas.save();
                canvas.clipRect(left, top, right, bottom);
                canvas.drawText(label, labelX, labelY, labelPaint);
                canvas.restore();
            }
        }
    }
}
