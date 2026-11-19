"""Launch the public controller from the macOS bundle, without Homebrew."""
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import urlencode
import webbrowser
from browser_launcher import open_control_page


root = Path(sys._MEIPASS)
tool_dir = root / "scrcpy-macos-aarch64-v4.0"
for variable, name in (
    ("AURA_ADB", "adb"),
    ("AURA_SCRCPY", "scrcpy"),
    ("AURA_SCRCPY_SERVER", "scrcpy-server"),
):
    os.environ.setdefault(variable, str(tool_dir / name))

from server import PORT, VERSION, main as run_server


HOST = "127.0.0.1"
URL = f"http://{HOST}:{PORT}/?{urlencode({'v': VERSION})}"


def server_is_ready():
    connection = http.client.HTTPConnection(HOST, PORT, timeout=0.5)
    try:
        connection.request("GET", "/api/version")
        response = connection.getresponse()
        if response.status != 200:
            return False
        data = json.loads(response.read(4096))
        return isinstance(data, dict) and data.get("version") == data.get("page_version") == VERSION
    except (OSError, ValueError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def alert(message):
    subprocess.run(["/usr/bin/osascript", "-e", "on run argv", "-e",
                    'display alert "Aura Control Public" message (item 1 of argv) as warning',
                    "-e", "end run", message], check=False)


def main():
    if server_is_ready():
        open_control_page(URL)
        return
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return alert(f"Port {PORT} is in use. Quit the other local controller first.")
    except OSError:
        pass
    worker = threading.Thread(target=run_server, daemon=True)
    worker.start()
    for _ in range(30):
        if server_is_ready():
            open_control_page(URL)
            worker.join()
            return
        if not worker.is_alive():
            break
        time.sleep(0.2)
    alert("The local control server did not start. Check the installation and try again.")


if __name__ == "__main__":
    main()
