#!/usr/bin/env python3
"""
dispatch_server.py
==================
Dispatch Desktop WebSocket Server.

Responsibilities
----------------
1. Detect open Terminal / Command-Prompt windows on the current desktop and
   broadcast their coordinates (id, title, x, y, width, height) as a JSON
   message to every connected Android client.
2. Listen for incoming JSON ``command`` messages from the Android app and:
   a. Focus the target terminal window.
   b. Type the received text into it.
   c. Press Enter.

Supported platforms
-------------------
* **Linux / X11** – uses ``wmctrl`` CLI tool to enumerate and focus windows.
  Install with ``sudo apt install wmctrl``.
* **Windows** – uses ``pygetwindow`` (``pip install pygetwindow``).
* **macOS** – uses ``AppKit`` / ``Quartz`` (available via the standard macOS
  Python installation or ``pip install pyobjc``).

Usage
-----
    python dispatch_server.py [--host HOST] [--port PORT]

    Defaults: HOST=0.0.0.0  PORT=8765
"""

import asyncio
import json
import logging
import platform
import subprocess
import sys
import time
import argparse
from typing import Any, Dict, List, Optional, Set

import websockets
from websockets.server import WebSocketServerProtocol

try:
    import pyautogui
    pyautogui.FAILSAFE = True  # Move mouse to top-left corner to abort.
    pyautogui.PAUSE = 0.05     # Small pause between pyautogui calls.
except ImportError:
    pyautogui = None  # type: ignore

# ── Logging setup ──────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("dispatch")

# ── Platform detection ─────────────────────────────────────────────────────────
_PLATFORM = platform.system()  # "Linux", "Windows", "Darwin"

# ── Terminal window keywords (case-insensitive title matching) ─────────────────
TERMINAL_KEYWORDS = [
    "terminal", "konsole", "gnome-terminal", "xterm", "bash", "zsh",
    "command prompt", "cmd", "powershell", "windows terminal",
    "iterm", "alacritty", "kitty", "wezterm",
]


# ════════════════════════════════════════════════════════════════════════════════
# Window detection helpers (per-platform)
# ════════════════════════════════════════════════════════════════════════════════

def _is_terminal(title: str) -> bool:
    """Return True if *title* looks like a terminal window."""
    title_lower = title.lower()
    return any(kw in title_lower for kw in TERMINAL_KEYWORDS)


# ── Linux / X11 ───────────────────────────────────────────────────────────────

def _detect_windows_linux() -> List[Dict[str, Any]]:
    """
    Use ``wmctrl -lG`` to list all visible windows on X11.

    Output format (space-separated):
        <wid> <desktop> <x> <y> <w> <h> <host> <title...>
    """
    windows: List[Dict[str, Any]] = []
    try:
        result = subprocess.run(
            ["wmctrl", "-lG"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode != 0:
            log.warning("wmctrl returned non-zero exit code. Is it installed?")
            return windows

        for line in result.stdout.strip().splitlines():
            parts = line.split(None, 7)
            if len(parts) < 8:
                continue
            wid, _desk, x, y, w, h, _host, title = parts
            if _is_terminal(title):
                windows.append({
                    "id":     int(wid, 16),  # wmctrl uses hex window IDs
                    "title":  title,
                    "x":      int(x),
                    "y":      int(y),
                    "width":  int(w),
                    "height": int(h),
                })
    except FileNotFoundError:
        log.warning("wmctrl not found. Install it: sudo apt install wmctrl")
    except subprocess.TimeoutExpired:
        log.error("wmctrl timed out.")
    return windows


def _focus_window_linux(window_id: int) -> None:
    """Bring the given X11 window to the foreground using wmctrl."""
    hex_id = hex(window_id)
    subprocess.run(["wmctrl", "-ia", hex_id], check=True, timeout=5)
    time.sleep(0.2)  # Give the WM time to complete the focus.


# ── Windows ───────────────────────────────────────────────────────────────────

def _detect_windows_win() -> List[Dict[str, Any]]:
    """Enumerate visible windows using pygetwindow on Windows."""
    windows: List[Dict[str, Any]] = []
    try:
        import pygetwindow as gw  # type: ignore
        for idx, win in enumerate(gw.getAllWindows()):
            if not win.title or not _is_terminal(win.title):
                continue
            if win.width <= 0 or win.height <= 0:
                continue
            windows.append({
                "id":     idx,
                "title":  win.title,
                "x":      win.left,
                "y":      win.top,
                "width":  win.width,
                "height": win.height,
            })
    except ImportError:
        log.warning("pygetwindow not installed. Run: pip install pygetwindow")
    return windows


def _focus_window_win(window_title: str) -> None:
    """Focus a Windows window by title using pygetwindow."""
    try:
        import pygetwindow as gw  # type: ignore
        wins = gw.getWindowsWithTitle(window_title)
        if wins:
            wins[0].activate()
            time.sleep(0.2)
    except Exception as exc:
        log.error("Failed to focus window '%s': %s", window_title, exc)


# ── macOS ─────────────────────────────────────────────────────────────────────

def _detect_windows_macos() -> List[Dict[str, Any]]:
    """Enumerate visible windows on macOS using Quartz / CoreGraphics."""
    windows: List[Dict[str, Any]] = []
    try:
        import Quartz  # type: ignore
        window_list = Quartz.CGWindowListCopyWindowInfo(
            Quartz.kCGWindowListOptionOnScreenOnly |
            Quartz.kCGWindowListExcludeDesktopElements,
            Quartz.kCGNullWindowID
        )
        for idx, win in enumerate(window_list):
            title = win.get("kCGWindowName", "") or ""
            owner = win.get("kCGWindowOwnerName", "") or ""
            combined = f"{title} {owner}"
            if not _is_terminal(combined):
                continue
            bounds = win.get("kCGWindowBounds", {})
            windows.append({
                "id":     win.get("kCGWindowNumber", idx),
                "title":  title or owner,
                "x":      int(bounds.get("X", 0)),
                "y":      int(bounds.get("Y", 0)),
                "width":  int(bounds.get("Width", 0)),
                "height": int(bounds.get("Height", 0)),
            })
    except ImportError:
        log.warning("Quartz not available. Install pyobjc: pip install pyobjc")
    return windows


def _focus_window_macos(window_id: int) -> None:
    """Raise a macOS window using AppleScript (fallback)."""
    script = (
        f'tell application "System Events" to set frontmost of '
        f'(first process whose unix id is {window_id}) to true'
    )
    subprocess.run(["osascript", "-e", script], timeout=5)
    time.sleep(0.2)


# ── Dispatcher ────────────────────────────────────────────────────────────────

def detect_terminal_windows() -> List[Dict[str, Any]]:
    """Return a list of terminal window descriptors for the current platform."""
    if _PLATFORM == "Linux":
        return _detect_windows_linux()
    elif _PLATFORM == "Windows":
        return _detect_windows_win()
    elif _PLATFORM == "Darwin":
        return _detect_windows_macos()
    else:
        log.warning("Unsupported platform: %s", _PLATFORM)
        return []


def focus_window(window: Dict[str, Any]) -> None:
    """Bring *window* to the foreground on the current platform."""
    try:
        if _PLATFORM == "Linux":
            _focus_window_linux(window["id"])
        elif _PLATFORM == "Windows":
            _focus_window_win(window["title"])
        elif _PLATFORM == "Darwin":
            _focus_window_macos(window["id"])
    except Exception as exc:
        log.error("focus_window failed: %s", exc)


def inject_text(text: str) -> None:
    """Type *text* into the focused window and press Enter via PyAutoGUI."""
    if pyautogui is None:
        log.error("pyautogui is not installed. Run: pip install pyautogui")
        return
    # pyautogui.write() supports unicode characters on newer versions.
    pyautogui.write(text, interval=0.02)
    pyautogui.press("enter")
    log.info("Injected text: %r", text)


# ════════════════════════════════════════════════════════════════════════════════
# WebSocket server logic
# ════════════════════════════════════════════════════════════════════════════════

# Set of all currently connected clients.
CLIENTS: Set[WebSocketServerProtocol] = set()

# Cache of the last detected window list (re-used between broadcasts).
_last_windows: List[Dict[str, Any]] = []


async def broadcast_windows() -> None:
    """
    Periodically detect terminal windows and broadcast them to all clients.

    Sends a message of the form:
    .. code-block:: json

        { "type": "windows", "data": [ { "id": …, "title": …, … }, … ] }
    """
    while True:
        windows = detect_terminal_windows()
        log.info("Detected %d terminal window(s).", len(windows))

        if windows:
            for w in windows:
                log.info(
                    "  id=%-6s  %4dx%-4d @ (%d,%d)  %s",
                    w["id"], w["width"], w["height"],
                    w["x"], w["y"], w["title"]
                )

        global _last_windows
        _last_windows = windows

        if CLIENTS:
            message = json.dumps({"type": "windows", "data": windows})
            # asyncio.gather sends to all clients concurrently.
            await asyncio.gather(
                *(client.send(message) for client in CLIENTS),
                return_exceptions=True,  # Do not abort if one send fails.
            )

        await asyncio.sleep(5)  # Re-scan every 5 seconds.


async def handle_command(data: Dict[str, Any]) -> None:
    """
    Process a ``command`` message from the Android app.

    Expected payload:
    .. code-block:: json

        { "type": "command", "window_id": 0, "text": "ls -la" }
    """
    window_id: Optional[int] = data.get("window_id")
    text: Optional[str] = data.get("text")

    if window_id is None or text is None:
        log.warning("Malformed command message: %s", data)
        return

    # Find the target window in the cached list.
    target = next((w for w in _last_windows if w["id"] == window_id), None)
    if target is None:
        log.warning("Window id=%s not found in cached window list.", window_id)
        # Refresh and try once more.
        _last_windows.clear()
        _last_windows.extend(detect_terminal_windows())
        target = next((w for w in _last_windows if w["id"] == window_id), None)
        if target is None:
            log.error("Window id=%s still not found after refresh.", window_id)
            return

    log.info("Handling command → window=%s (%s) text=%r",
             window_id, target.get("title"), text)

    # 1. Focus the window.
    focus_window(target)

    # 2. Small delay to ensure the WM has processed the focus change.
    await asyncio.sleep(0.3)

    # 3. Inject text + Enter.
    inject_text(text)


async def client_handler(websocket: WebSocketServerProtocol) -> None:
    """Handle a single WebSocket client connection."""
    client_addr = websocket.remote_address
    log.info("Client connected: %s", client_addr)
    CLIENTS.add(websocket)

    # Immediately send the current window list to the new client.
    if _last_windows:
        try:
            await websocket.send(
                json.dumps({"type": "windows", "data": _last_windows})
            )
        except Exception as exc:
            log.warning("Failed to send initial window list: %s", exc)

    try:
        async for raw_message in websocket:
            log.debug("Received from %s: %s", client_addr, raw_message)
            try:
                data = json.loads(raw_message)
            except json.JSONDecodeError:
                log.warning("Non-JSON message received: %r", raw_message)
                continue

            msg_type = data.get("type")
            if msg_type == "command":
                await handle_command(data)
            else:
                log.warning("Unknown message type: %r", msg_type)

    except websockets.exceptions.ConnectionClosedOK:
        log.info("Client %s disconnected cleanly.", client_addr)
    except websockets.exceptions.ConnectionClosedError as exc:
        log.warning("Client %s connection error: %s", client_addr, exc)
    finally:
        CLIENTS.discard(websocket)
        log.info("Client %s removed. Active clients: %d", client_addr, len(CLIENTS))


async def main(host: str, port: int) -> None:
    """Start the WebSocket server and the window-broadcast background task."""
    log.info("Starting Dispatch server on %s:%d (platform: %s)", host, port, _PLATFORM)

    # Perform an initial window detection to populate the cache.
    _last_windows.extend(detect_terminal_windows())
    if not _last_windows:
        log.warning(
            "No terminal windows found at startup. "
            "The server will re-scan every 5 seconds."
        )

    # Run the broadcast loop as a background task.
    broadcast_task = asyncio.create_task(broadcast_windows())

    async with websockets.serve(client_handler, host, port):
        log.info("Dispatch server is ready. Waiting for Android client connections…")
        try:
            await asyncio.Future()  # Run forever until interrupted.
        finally:
            broadcast_task.cancel()
            try:
                await broadcast_task
            except asyncio.CancelledError:
                pass


# ── CLI entry-point ────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Dispatch WebSocket server – turns a Python desktop into a"
                    " voice-controlled terminal hub."
    )
    parser.add_argument(
        "--host", default="0.0.0.0",
        help="IP address to bind to (default: 0.0.0.0)"
    )
    parser.add_argument(
        "--port", type=int, default=8765,
        help="TCP port to listen on (default: 8765)"
    )
    args = parser.parse_args()

    try:
        asyncio.run(main(args.host, args.port))
    except KeyboardInterrupt:
        log.info("Server stopped by user (KeyboardInterrupt).")
        sys.exit(0)
