#!/usr/bin/env python3
import atexit
import json
import os
import re
import signal
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlsplit


ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
DATA_ROOT = Path(os.environ.get("AURA_PUBLIC_DATA_DIR", Path.home() / "Library" / "Application Support" / "Aura Control Public"))
SCRCPY = Path(os.environ.get("AURA_SCRCPY", shutil.which("scrcpy") or "scrcpy"))
ADB = Path(os.environ.get("AURA_ADB", shutil.which("adb") or "adb"))
SCRCPY_SERVER = Path(os.environ.get("AURA_SCRCPY_SERVER", "/opt/homebrew/share/scrcpy/scrcpy-server"))
RECORDINGS = DATA_ROOT / "recordings"
LOG_FILE = DATA_ROOT / "scrcpy.log"
PORT = int(os.environ.get("AURA_CONTROL_PORT", "8775"))

process_lock = threading.Lock()
wireless_lock = threading.Lock()
monitor_stop = threading.Event()
monitor_wake = threading.Event()
discovery_lock = threading.Lock()
discovery = {"automatic": False, "pending": False, "running": False, "generation": 0}
preferred_wireless = None
streams = {}
last_errors = {"1": "", "2": "", "3": ""}
wireless_error = ""
verified_wireless = {}
wireless_bootstrap_attempts = set()

APPS = {"calendar": "com.google.android.calendar","chrome": "com.android.chrome","docs": "com.google.android.apps.docs.editors.docs","drive": "com.google.android.apps.docs","gemini": "com.google.android.apps.bard","gmail": "com.google.android.gm","google-maps": "com.google.android.apps.maps","photos": "com.google.android.apps.photos","sheets": "com.google.android.apps.docs.editors.sheets","slides": "com.google.android.apps.docs.editors.slides","youtube": "com.google.android.youtube"}
GEMINI_PACKAGE = "com.google.android.apps.bard"


def adb_devices():
    result = subprocess.run(
        [str(ADB), "devices", "-l"], capture_output=True, text=True, timeout=5
    )
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            properties = dict(
                item.split(":", 1) for item in parts[2:] if ":" in item
            )
            devices.append({
                "serial": parts[0],
                "usb": any(item.startswith("usb:") for item in parts[2:]),
                "model": properties.get("model", "Android"),
            })
    return devices


def device_choices():
    choices = []
    for device in adb_devices():
        try:
            result = subprocess.run(
                [str(ADB), "-s", device["serial"], "shell", "getprop", "ro.serialno"],
                capture_output=True, text=True, timeout=2,
            )
        except subprocess.TimeoutExpired:
            continue
        if result.returncode:
            continue
        choices.append({
            "serial": device["serial"],
            "transport": "usb" if device["usb"] else "wireless",
            "model": device["model"].replace("_", " "),
            "physical_serial": result.stdout.strip() or device["serial"],
        })
    return choices


def connection_state():
    devices = adb_devices()
    usb = next((item["serial"] for item in devices if item["usb"]), None)
    wireless = [
        item["serial"] for item in devices
        if not item["usb"] and ":" in item["serial"]
    ]
    return usb, wireless


def device_wifi_ip(serial):
    result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "ip", "-4", "addr", "show", "wlan0"],
        capture_output=True, text=True, timeout=5,
    )
    match = re.search(r"\binet\s+(\d+(?:\.\d+){3})/", result.stdout)
    return match.group(1) if match else None


def wireless_is_ready(serial):
    result = subprocess.run(
        [str(ADB), "-s", serial, "get-state"],
        capture_output=True, text=True, timeout=5,
    )
    return result.returncode == 0 and result.stdout.strip() == "device"


def confirm_wireless(target, expected):
    if not expected or not wireless_is_ready(target):
        return False
    actual = subprocess.run(
        [str(ADB), "-s", target, "shell", "getprop", "ro.serialno"],
        capture_output=True, text=True, timeout=5,
    )
    if actual.returncode or actual.stdout.strip() != expected:
        return False
    verified_wireless[target] = {"serial": expected, "address": target}
    return True


def ensure_wireless(discover=True, cancelled=lambda: False):
    global preferred_wireless, wireless_error
    with wireless_lock:
        usb, wireless_devices = connection_state()
        usb = next((d["serial"] for d in adb_devices() if d.get("usb") and "Android_XR_Puck" in d.get("model", "")), None)
        if not usb or not discover:
            # Only retry addresses with a previously verified physical identity.
            known_targets = list(dict.fromkeys([preferred_wireless, *verified_wireless]))
            for target in known_targets:
                known = verified_wireless.get(target)
                if not known:
                    continue
                expected = known["serial"]
                if not wireless_is_ready(target):
                    subprocess.run(
                        [str(ADB), "disconnect", target],
                        capture_output=True, text=True, timeout=5,
                    )
                    subprocess.run(
                        [str(ADB), "connect", target],
                        capture_output=True, text=True, timeout=8,
                    )
                if confirm_wireless(target, expected):
                    preferred_wireless = target
                    wireless_error = ""
                    return None, target
                wireless_error = f"Wi-Fi接続に失敗しました：{target}"
            candidates = [item for item in wireless_devices if item not in verified_wireless]
            for target in candidates:
                # Existing authorized ADB sessions survive a controller restart.
                identity = subprocess.run(
                    [str(ADB), "-s", target, "shell", "getprop", "ro.serialno"],
                    capture_output=True, text=True, timeout=5,
                )
                if identity.returncode == 0 and confirm_wireless(target, identity.stdout.strip()):
                    preferred_wireless = target
                    wireless_error = ""
                    return None, target
            return None, None

        if cancelled():
            return usb, None
        ip_address = device_wifi_ip(usb)
        if not ip_address:
            wireless_error = "デバイスのWi-Fiアドレスを取得できません。"
            return usb, None
        target = f"{ip_address}:5555"
        expected = subprocess.run([str(ADB), "-s", usb, "shell", "getprop", "ro.serialno"], capture_output=True, text=True, timeout=5).stdout.strip()
        if target in wireless_devices and confirm_wireless(target, expected):
            preferred_wireless = target
            wireless_error = ""
            return usb, target

        if cancelled():
            return usb, None
        subprocess.run(
            [str(ADB), "connect", target], capture_output=True, text=True, timeout=8
        )
        if not wireless_is_ready(target):
            with process_lock:
                cleanup_streams()
                usb_in_use = any(
                    state.get("serial") == usb and state.get("connection") == "usb"
                    for state in streams.values()
                )
                attempt = (expected, target)
                if expected and not usb_in_use and attempt not in wireless_bootstrap_attempts and not cancelled():
                    # Network failure must not repeatedly restart the working USB transport.
                    wireless_bootstrap_attempts.add(attempt)
                    tcp_port = subprocess.run(
                        [str(ADB), "-s", usb, "shell", "getprop", "service.adb.tcp.port"],
                        capture_output=True, text=True, timeout=5,
                    )
                    if tcp_port.returncode == 0 and tcp_port.stdout.strip() != "5555" and not cancelled():
                        subprocess.run(
                            [str(ADB), "-s", usb, "tcpip", "5555"],
                            capture_output=True, text=True, timeout=8,
                        )
                        time.sleep(1)
                        if cancelled():
                            return usb, None
                        subprocess.run(
                            [str(ADB), "connect", target], capture_output=True, text=True, timeout=8
                        )

        if confirm_wireless(target, expected):
            preferred_wireless = target
            wireless_error = ""
            return usb, target
        wireless_error = f"Wi-Fi接続に失敗しました：{target}"
        return usb, None


def discovery_status():
    with discovery_lock:
        return {key: discovery[key] for key in ("automatic", "pending", "running")}


def configure_discovery(options):
    automatic = options.get("automatic")
    once = options.get("once", False)
    if (automatic is not None and not isinstance(automatic, bool)) or not isinstance(once, bool):
        raise ValueError("検出設定が正しくありません。")
    if automatic is None and not once:
        raise ValueError("検出設定が正しくありません。")
    with discovery_lock:
        discovery["generation"] += 1
        if automatic is not None:
            discovery["automatic"] = automatic
        discovery["pending"] = bool(once or automatic)
    monitor_wake.set()
    return discovery_status()


def wireless_monitor_step():
    with discovery_lock:
        discover = discovery["automatic"] or discovery["pending"]
        generation = discovery["generation"]
        if discover:
            discovery["pending"] = False
            discovery["running"] = True
    def cancelled():
        with discovery_lock:
            return generation != discovery["generation"]
    try:
        # Recovery uses verified addresses only; turning discovery off never closes ADB.
        _, connected = ensure_wireless(discover=discover, cancelled=cancelled)
        if connected and discover:
            with discovery_lock:
                if generation == discovery["generation"]:
                    discovery["automatic"] = False
                    discovery["pending"] = False
    finally:
        with discovery_lock:
            discovery["running"] = False


def monitor_wireless():
    while not monitor_stop.is_set():
        monitor_wake.clear()
        try:
            wireless_monitor_step()
        except Exception:
            pass
        monitor_wake.wait(3)


def cleanup_streams():
    for slot, state in list(streams.items()):
        if state["process"].poll() is not None:
            streams.pop(slot, None)


def stop_scrcpy(slot):
    slot = str(slot)
    if slot not in {"1", "2", "3"}:
        raise ValueError("投影枠が正しくありません。")
    with process_lock:
        cleanup_streams()
        state = streams.pop(slot, None)
        if not state:
            return False
        process = state["process"]
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=2)
        return True


def stop_all_scrcpy():
    for slot in list(streams):
        try:
            stop_scrcpy(slot)
        except Exception:
            pass


def resolve_target(requested_target):
    if requested_target == "auto":
        usb, wireless_devices = connection_state()
        if usb:
            return usb
        if preferred_wireless in wireless_devices:
            return preferred_wireless
        return next(iter(wireless_devices), None)
    devices = {item["serial"] for item in adb_devices()}
    if requested_target not in devices:
        raise RuntimeError("選択した接続は現在利用できません。")
    return requested_target


def launch_app(options):
    app_id = str(options.get("app", ""))
    if app_id not in APPS:
        raise ValueError("アプリが正しくありません。")
    serial = resolve_target(str(options.get("target", "auto")))
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")
    command = [str(ADB), "-s", serial, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", APPS[app_id]]
    result = subprocess.run(
        command,
        capture_output=True, text=True, timeout=15,
    )
    output = f"{result.stdout}\n{result.stderr}".strip()
    if result.returncode or "Error:" in output or "Exception" in output:
        raise RuntimeError(output or "アプリを起動できません。")
    confirm_app_task(serial, APPS[app_id].split("/", 1)[0])
    return serial


def confirm_app_task(serial, package):
    """Check only after an action, not via periodic foreground ADB polling."""
    for attempt in range(3):
        result = subprocess.run(
            [str(ADB), "-s", serial, "shell", "dumpsys", "activity", "activities"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0 and re.search(
            r"(?:ActivityRecord\{[^\n]*|(?:mResumedActivity|topResumedActivity)[^\n]*)\b" + re.escape(package) + r"/", result.stdout
        ):
            return
        if attempt < 2:
            time.sleep(.15)
    raise RuntimeError("アプリの起動を確認できません。")


def app_presence(options):
    app = str(options.get("app", ""))
    package = GEMINI_PACKAGE if app == "gemini" else APPS.get(app, "").split("/", 1)[0]
    expected = str(options.get("physical_serial", ""))
    if not package or not expected:
        raise ValueError("アプリが正しくありません。")
    serial = resolve_target(str(options.get("target", "")))
    identity = subprocess.run([str(ADB), "-s", serial, "shell", "getprop", "ro.serialno"],
                              capture_output=True, text=True, timeout=3)
    if identity.returncode or identity.stdout.strip() != expected:
        return {"physical_serial": expected, "present": None}
    result = subprocess.run([str(ADB), "-s", serial, "shell", "dumpsys", "activity", "activities"],
                            capture_output=True, text=True, timeout=5)
    packages = re.findall(r"ActivityRecord\{[^\n]*?\bu\d+\s+([\w.]+)/", result.stdout)
    # An unknown/partial response is never proof that the guest closed the app.
    valid = not result.returncode and "ACTIVITY MANAGER ACTIVITIES" in result.stdout and bool(packages)
    return {"physical_serial": expected, "present": package in packages if valid else None}


def open_url(options):
    url = str(options.get("url", "")).strip()
    if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", url):
        url = f"https://{url}"
    parsed = urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("正しいWebサイトのURLを入力してください。")
    serial = resolve_target(str(options.get("target", "auto")))
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")
    result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "am", "start", "-W", "-n", "com.android.chrome/com.google.android.apps.chrome.Main", "-a", "android.intent.action.VIEW", "-d", url],
        capture_output=True, text=True, timeout=15,
    )
    output = f"{result.stdout}\n{result.stderr}".strip()
    if result.returncode or "Error:" in output or "Exception" in output:
        raise RuntimeError(output or "Webサイトを開けませんでした。")
    confirm_app_task(serial, "com.android.chrome")
    return serial, url


def tune_device(options):
    action = str(options.get("action", ""))
    volume_streams = {
        "media-volume": ("メディア音量", "3"),
        "system-volume": ("システム音量", "1"),
        "notification-volume": ("通知音量", "5"),
        "ai-volume": ("AI音量", "11"),
    }
    allowed = {*volume_streams, "gemini-on", "gemini-off"}
    if action not in allowed:
        raise ValueError("調整項目が正しくありません。")
    serial = resolve_target(str(options.get("target", "auto")))
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")

    if action in volume_streams:
        try:
            value = int(options.get("value"))
        except (TypeError, ValueError):
            raise ValueError("音量は0〜15で入力してください。")
        if not 0 <= value <= 15:
            raise ValueError("音量は0〜15で入力してください。")
        label, stream = volume_streams[action]
        result = subprocess.run(
            [str(ADB), "-s", serial, "shell", "cmd", "media_session", "volume", "--stream", stream, "--set", str(value)],
            capture_output=True, text=True, timeout=5,
        )
        output = f"{result.stdout}\n{result.stderr}".strip()
        if result.returncode or "Error" in output or "Exception" in output:
            raise RuntimeError(output or f"{label}を設定できません。")
        current = subprocess.run(
            [str(ADB), "-s", serial, "shell", "cmd", "media_session", "volume", "--stream", stream, "--get"],
            capture_output=True, text=True, timeout=5,
        )
        match = re.search(r"(?:volume is|volume:)\s*(\d+)", f"{current.stdout}\n{current.stderr}", re.I)
        if current.returncode or not match:
            raise RuntimeError(f"{label}の現在値を確認できません。")
        if int(match.group(1)) != value:
            raise RuntimeError(f"{label}を設定できませんでした（現在 {match.group(1)}/15）。")
        return serial, f"{label}：{value}"

    command = (["am", "force-stop", GEMINI_PACKAGE] if action == "gemini-off" else
               ["am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", GEMINI_PACKAGE])
    result = subprocess.run([str(ADB), "-s", serial, "shell", *command], capture_output=True, text=True, timeout=15, check=True)
    if re.search(r"error|exception|failed", result.stdout + result.stderr, re.I):
        raise RuntimeError("操作に失敗しました。")
    return serial, "OK"


def quarter_screen_size():
    try:
        result = subprocess.run(
            ["osascript", "-e", 'tell application "Finder" to get bounds of window of desktop'],
            capture_output=True, text=True, timeout=5, check=True,
        )
        left, top, right, bottom = [int(item.strip()) for item in result.stdout.split(",")]
        return max(320, (right - left) // 2), max(240, (bottom - top) // 2)
    except (OSError, ValueError, subprocess.SubprocessError):
        return 756, 491


def current_task(serial):
    result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "dumpsys", "activity", "activities"],
        capture_output=True, text=True, timeout=8,
    )
    match = re.search(
        r"(?:topDisplayFocusedRootTask|mPreferredTopFocusableRootTask)=Task\{([^\n]+)",
        result.stdout,
    )
    if not match:
        return None
    body = match.group(1)
    task_id = re.search(r"#(\d+)", body)
    package = re.search(r"\bA=\d+:([\w.]+)", body)
    if not task_id or not package:
        return None
    mode = re.search(
        rf"Task\{{[^\n}}]*#{task_id.group(1)}[^\n}}]*\bmode=([\w-]+)",
        result.stdout,
    )
    return int(task_id.group(1)), package.group(1), mode.group(1) if mode else ""


def control_device(options):
    action = str(options.get("action", ""))
    allowed = {"close-all", "back", "show-launcher"}
    if action not in allowed:
        raise ValueError("操作が正しくありません。")
    serial = resolve_target(str(options.get("target", "auto")))
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")

    if action == "back":
        subprocess.run([str(ADB), "-s", serial, "shell", "input", "keyevent", "KEYCODE_BACK"], timeout=5)
        return serial, "戻りました。"
    if action == "show-launcher":
        subprocess.run([str(ADB), "-s", serial, "shell", "input", "keyevent", "KEYCODE_HOME"], timeout=5, check=True)
        return serial, "OK"
    for package in APPS.values():
        subprocess.run([str(ADB), "-s", serial, "shell", "am", "force-stop", package], capture_output=True, text=True, timeout=5, check=True)
    return serial, "OK"


def internal_display_id(serial):
    result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "dumpsys", "display"],
        capture_output=True, text=True, timeout=8,
    )
    match = re.search(r'DisplayInfo\{".*?", displayId\s+(\d+).*?\btype INTERNAL\b', result.stdout)
    if not match:
        raise RuntimeError("内蔵画面の番号を取得できません。")
    return match.group(1)


def scrcpy_environment():
    for path in (SCRCPY, ADB, SCRCPY_SERVER):
        if not path.is_file():
            raise RuntimeError(f"投影コンポーネントが見つかりません：{path.name}")
    for path in (SCRCPY, ADB):
        if not os.access(path, os.X_OK):
            raise RuntimeError(f"投影コンポーネントを実行できません：{path.name}")
    return {
        **os.environ,
        "ADB": str(ADB.resolve()),
        "SCRCPY_SERVER_PATH": str(SCRCPY_SERVER.resolve()),
    }


def projection_settings(options, connection):
    # Resolve the real ADB transport first: client fields cannot downgrade USB.
    if connection == "usb":
        return {"resolution": "0", "frame_rate": "60", "audio": "both",
                "video_bit_rate": "20M", "audio_bit_rate": "256k"}
    resolution = str(options.get("resolution", "1280"))
    frame_rate = str(options.get("frame_rate", "30"))
    audio = options.get("audio", "both")
    if (resolution not in {"1280", "1600", "1920"}
            or frame_rate not in {"30", "60"}
            or audio not in {"no-audio", "playback", "output", "both"}):
        raise ValueError("設定値が正しくありません。")
    if options.get("record", False):
        return {"resolution": "0", "frame_rate": "60", "audio": audio,
                "video_bit_rate": "20M", "audio_bit_rate": "256k"}
    return {"resolution": resolution, "frame_rate": frame_rate, "audio": audio,
            "video_bit_rate": "8M", "audio_bit_rate": "64k" if audio == "both" else "128k"}


def open_recordings():
    RECORDINGS.mkdir(parents=True, exist_ok=True)
    subprocess.run(["/usr/bin/open", str(RECORDINGS)], check=True,
                   capture_output=True, text=True, timeout=10)
    return str(RECORDINGS)


def start_scrcpy(options):
    slot = str(options.get("slot", ""))
    if slot not in {"1", "2", "3"}:
        raise ValueError("設定値が正しくありません。")

    projection_label = {"ja": "投影", "zh": "投屏", "en": "Casting"}.get(str(options.get("language", "ja")))
    if projection_label is None:
        raise ValueError("言語設定が正しくありません。")

    requested_target = str(options.get("target", "auto"))
    serial = resolve_target(requested_target)
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")
    connection = "wireless" if ":" in serial else "usb"
    display_id = internal_display_id(serial)
    settings = projection_settings(options, connection)
    resolution = settings["resolution"]
    audio = settings["audio"]
    physical_result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "getprop", "ro.serialno"],
        capture_output=True, text=True, timeout=5,
    )
    physical_serial = physical_result.stdout.strip() or serial
    projection_env = scrcpy_environment()

    with process_lock:
        cleanup_streams()
        current = streams.get(slot)
        if current:
            process = current["process"]
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
            streams.pop(slot, None)

        if any(
            state["physical_serial"] == physical_serial
            for other_slot, state in streams.items() if other_slot != slot
        ):
            raise RuntimeError("このデバイスは別の投影枠で使用中です。")

        audio_adjusted = False
        if audio != "no-audio" and any(
            state["audio"] != "no-audio" for state in streams.values()
        ):
            audio = "no-audio"
            audio_adjusted = True

        window_width, window_height = quarter_screen_size()
        command = [
            str(SCRCPY), "-s", serial,
            f"--display-id={display_id}",
            f"--max-size={resolution}",
            f"--max-fps={settings['frame_rate']}",
            f"--video-bit-rate={settings['video_bit_rate']}",
            f"--window-width={window_width}",
            f"--window-height={window_height}",
            f"--window-title={projection_label} {slot} · {'Wi-Fi' if connection == 'wireless' else 'USB'} · {serial}",
        ]
        if options.get("always_top", False):
            command.append("--always-on-top")
        if options.get("mouse", False):
            command.append("--mouse=uhid")
        if options.get("record", False):
            RECORDINGS.mkdir(parents=True, exist_ok=True)
            filename = RECORDINGS / f"投影{slot}_{datetime.now():%Y%m%d_%H%M%S_%f}.mp4"
            command.append("--video-codec=h264")
            command += [f"--record={filename}", "--record-format=mp4"]

        if audio == "no-audio":
            command.append("--no-audio")
        elif audio == "playback":
            command += ["--audio-source=playback", "--audio-codec=aac",
                        f"--audio-bit-rate={settings['audio_bit_rate']}"]
        elif audio == "output":
            command += ["--audio-source=output", "--audio-codec=aac",
                        f"--audio-bit-rate={settings['audio_bit_rate']}"]
        else:
            command += [
                "--audio-source=playback", "--audio-dup",
                "--audio-buffer=100", "--audio-codec=aac",
                f"--audio-bit-rate={settings['audio_bit_rate']}",
            ]

        log_path = DATA_ROOT / f"scrcpy-{slot}.log"
        log = log_path.open("ab")
        try:
            process = subprocess.Popen(
                command,
                cwd=ROOT,
                env=projection_env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            streams[slot] = {
                "process": process,
                "serial": serial,
                "physical_serial": physical_serial,
                "connection": connection,
                "audio": audio,
                "resolution": resolution,
                "frame_rate": settings["frame_rate"],
            }
        finally:
            log.close()

        try:
            exit_code = process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            last_errors[slot] = ""
            return serial, connection, command, audio, audio_adjusted

        streams.pop(slot, None)
        tail = log_path.read_text(errors="replace")[-1200:] if log_path.exists() else ""
        last_errors[slot] = tail.strip() or f"scrcpy が終了しました（終了コード {exit_code}）。"
        raise RuntimeError(last_errors[slot])


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, format, *args):
        pass

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/devices":
            try:
                self.send_json(200, {"devices": device_choices()})
            except Exception as error:
                self.send_json(500, {"error": str(error)})
            return
        if self.path == "/api/status":
            try:
                with process_lock:
                    cleanup_streams()
                usb, wireless_devices = connection_state()
                wireless = (
                    preferred_wireless
                    if preferred_wireless in wireless_devices
                    else wireless_devices[0] if wireless_devices else None
                )
                self.send_json(200, {
                    "device_connected": bool(usb or wireless),
                    "usb_connected": bool(usb),
                    "usb_serial": usb,
                    "wireless_connected": bool(wireless),
                    "wireless_serial": wireless,
                    "streams": {
                        slot: {
                            "running": slot in streams,
                            "active_serial": streams.get(slot, {}).get("serial"),
                            "active_connection": streams.get(slot, {}).get("connection"),
                            "audio": streams.get(slot, {}).get("audio"),
                            "resolution": streams.get(slot, {}).get("resolution"),
                            "frame_rate": streams.get(slot, {}).get("frame_rate"),
                            "error": last_errors[slot],
                        }
                        for slot in ("1", "2", "3")
                    },
                    "wireless_error": wireless_error,
                    "verified_wireless": list(verified_wireless.values()),
                    "discovery": discovery_status(),
                })
            except Exception as error:
                self.send_json(500, {"error": str(error)})
            return
        super().do_GET()

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 4096:
                raise ValueError("リクエストが大きすぎます。")
            data = json.loads(self.rfile.read(length) or b"{}")

            if self.path == "/api/start":
                serial, connection, command, audio, audio_adjusted = start_scrcpy(data)
                self.send_json(200, {
                    "success": True,
                    "serial": serial,
                    "connection": connection,
                    "already_running": command is None,
                    "audio": audio,
                    "audio_adjusted": audio_adjusted,
                })
            elif self.path == "/api/stop":
                slot = str(data.get("slot"))
                stopped = stop_scrcpy(slot)
                self.send_json(200, {"success": True, "stopped": stopped})
            elif self.path == "/api/open-recordings":
                self.send_json(200, {"success": True, "path": open_recordings()})
            elif self.path == "/api/launch":
                self.send_json(200, {"success": True, "serial": launch_app(data)})
            elif self.path == "/api/app-presence":
                self.send_json(200, app_presence(data))
            elif self.path == "/api/discovery":
                self.send_json(200, configure_discovery(data))
            elif self.path == "/api/open-url":
                serial, url = open_url(data)
                self.send_json(200, {"success": True, "serial": serial, "url": url})
            elif self.path == "/api/tune":
                serial, message = tune_device(data)
                self.send_json(200, {"success": True, "serial": serial, "message": message})
            elif self.path == "/api/control":
                serial, message = control_device(data)
                self.send_json(200, {"success": True, "serial": serial, "message": message})
            else:
                self.send_json(404, {"error": "APIが見つかりません。"})
        except Exception as error:
            self.send_json(400, {"error": str(error)})


def main():
    if not shutil.which(str(SCRCPY)) or not shutil.which(str(ADB)):
        raise SystemExit("scrcpy v4.0 が見つかりません。")
    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    atexit.register(stop_all_scrcpy)
    if os.environ.get("AURA_CONTROL_AUTO_WIFI", "1") != "0":
        threading.Thread(target=monitor_wireless, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"投影コントロール: http://127.0.0.1:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        monitor_stop.set()
        monitor_wake.set()
        stop_all_scrcpy()
        server.server_close()


if __name__ == "__main__":
    main()
