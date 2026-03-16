# Dispatch

> **Dispatch** turns your Android phone into a walkie-talkie for controlling
> desktop terminal windows – using voice commands and hardware volume buttons.

---

## Architecture

```
┌─────────────────────────────────┐          WebSocket (LAN)
│   Android App (Java)            │ ◄────────────────────────► ┌─────────────────────────────┐
│                                 │                             │   Python Desktop Server     │
│  • DesktopWindowView (Canvas)   │                             │                             │
│  • Volume button intercept      │                             │  • Detects terminal windows │
│  • SpeechRecognizer (STT)       │                             │  • Broadcasts coordinates   │
│  • OkHttp WebSocket client      │                             │  • Injects text (PyAutoGUI) │
└─────────────────────────────────┘                             └─────────────────────────────┘
```

---

## Features

| Feature | Description |
|---|---|
| Window detection | Finds open terminal windows on the desktop and sends their coordinates to the phone |
| Canvas visualisation | Android app draws proportional rectangles on screen representing each desktop window |
| Volume UP | Cycles the highlighted/selected window on the phone canvas |
| Volume DOWN (hold) | Activates speech-to-text (window turns red) |
| Volume DOWN (release) | Stops recording, transcribes speech, sends text → desktop server → target terminal |
| Cross-platform server | Supports Linux/X11 (`wmctrl`), Windows (`pygetwindow`), macOS (Quartz) |

---

## Project Structure

```
Dispatch/
├── server/
│   ├── dispatch_server.py   # Python WebSocket server
│   └── requirements.txt     # Python dependencies
└── android/
    ├── app/src/main/
    │   ├── java/com/dispatch/app/
    │   │   ├── MainActivity.java         # Activity: WS + volume buttons + STT
    │   │   ├── DesktopWindowView.java    # Custom Canvas View
    │   │   └── WebSocketManager.java     # OkHttp WebSocket wrapper
    │   ├── res/layout/activity_main.xml  # UI layout
    │   ├── res/values/strings.xml
    │   ├── res/values/colors.xml
    │   └── AndroidManifest.xml
    ├── app/build.gradle
    ├── build.gradle
    ├── settings.gradle
    └── gradle.properties
```

---

## Getting Started

### 1 – Desktop Server (Python ≥ 3.9)

```bash
cd server
pip install -r requirements.txt
```

**Platform prerequisites:**

| Platform | Requirement |
|---|---|
| Linux/X11 | `sudo apt install wmctrl` |
| Windows | `pip install pygetwindow` (already in requirements) |
| macOS | `pip install pyobjc` |

**Run the server:**

```bash
python dispatch_server.py --host 0.0.0.0 --port 8765
```

The server will print detected terminal windows and their coordinates.

> **Note:** Make sure your desktop firewall allows inbound connections on port 8765.

---

### 2 – Android App

Open `android/` in **Android Studio** (Electric Eel or newer).

1. Ensure the device/emulator has **network access** to your desktop PC.
2. Build & run the app (`Shift+F10`).
3. On the app's main screen, enter the server URL (e.g. `ws://192.168.1.10:8765`) and tap **Connect**.

**Permissions required:**
- `INTERNET` – WebSocket communication.
- `RECORD_AUDIO` – Voice commands.

---

## Usage

Once connected:

| Action | Result |
|---|---|
| **Volume UP** (short press) | Cycle selection to the next terminal window (green highlight) |
| **Volume DOWN** (hold) | Window turns **red** → start speaking your command |
| **Volume DOWN** (release) | Speech is transcribed → text is sent to the selected terminal |

The Python server receives the command, focuses the target terminal window, types the text, and presses Enter.

---

## WebSocket Message Protocol

### Server → Client

```json
{
  "type": "windows",
  "data": [
    { "id": 12345, "title": "Terminal", "x": 0, "y": 0, "width": 800, "height": 600 }
  ]
}
```

### Client → Server

```json
{
  "type": "command",
  "window_id": 12345,
  "text": "ls -la"
}
```

---

## Development Notes

- The server re-scans for terminal windows every **5 seconds** and broadcasts
  the updated list to all connected clients.
- PyAutoGUI's `FAILSAFE` is enabled – move the mouse to the top-left corner of
  the screen to abort any runaway injection.
- The Android app requests the `RECORD_AUDIO` permission at runtime. Deny it and
  voice commands will be unavailable.