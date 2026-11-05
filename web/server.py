#!/usr/bin/env python3
import atexit
import ipaddress
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
VERSION = (ROOT / "VERSION").read_text().strip()
PAGE_ASSETS = {"/", "/index.html", "/ui_languages.js", "/i18n.js", "/experience_timers.js"}

process_lock = threading.Lock()
wireless_lock = threading.Lock()
monitor_stop = threading.Event()
monitor_wake = threading.Event()
discovery_lock = threading.Lock()
discovery = {"automatic": False, "pending": False, "running": False, "generation": 0}
preferred_wireless = None
streams = {}
recoveries = {}
WIFI_RECOVERY_GRACE = 18
WIFI_RECOVERY_STABLE = 20
last_errors = {"1": "", "2": "", "3": ""}
wireless_error = ""
verified_wireless = {}
wireless_bootstrap_attempts = set()
wireless_retry_after = {}

APPS = {
    "maps-xr": "com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity",
    "maps-new": "com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity",
    "google-maps": "com.google.android.apps.maps/com.google.android.maps.MapsActivity",
    "chrome": "com.android.chrome/com.google.android.apps.chrome.Main",
    "youtube": "com.google.android.apps.youtube.xr/com.google.android.apps.youtube.vr.activities.YouTubeVrPanel2Activity",
}

GROUP_APPS = [
    ["-n", "com.google.android.gm/.ConversationListActivityGmail"],
    ["-n", "com.google.android.apps.docs.editors.docs/com.google.android.apps.docs.app.NewMainProxyActivity"],
    ["-n", APPS["youtube"]],
    ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", "com.google.android.apps.photos"],
    ["-n", APPS["google-maps"]],
]
DESTINATIONS = {"tokyo-tower": "東京タワー", "takeshiba": "東京ポートシティ竹芝", "skytree": "東京スカイツリー"}
GEMINI_PACKAGE = "com.google.android.googlequicksearchbox"


def adb_devices(timeout=5):
    result = subprocess.run(
        [str(ADB), "devices", "-l"], capture_output=True, text=True, timeout=timeout
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


def device_wifi_ip(serial, timeout=5):
    result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "ip", "-4", "addr", "show", "wlan0"],
        capture_output=True, text=True, timeout=timeout,
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
    remember_wireless(target, expected)
    return True


def remember_wireless(target, expected):
    for address, known in list(verified_wireless.items()):
        if address != target and known["serial"] == expected:
            verified_wireless.pop(address, None)
    verified_wireless[target] = {"serial": expected, "address": target}


def advertised_wireless_address(expected, timeout=3):
    """Recover only a known serial's legacy ADB advertisement, never TLS pairing."""
    try:
        result = subprocess.run([str(ADB), "mdns", "services"],
                                capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode:
        return None
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) != 3 or parts[0] != f"adb-{expected}" or parts[1].rstrip(".") != "_adb._tcp":
            continue
        host, separator, port = parts[2].rpartition(":")
        try:
            if separator and 1 <= int(port) <= 65535 and ipaddress.ip_address(host).version == 4:
                return parts[2]
        except ValueError:
            continue
    return None


def read_physical_serial(target, timeout=2):
    try:
        result = subprocess.run(
            [str(ADB), "-s", target, "shell", "getprop", "ro.serialno"],
            capture_output=True, text=True, timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return (result.stdout.strip() or None) if result.returncode == 0 else None


def recovery_target(physical_serial, prefer_usb):
    """Find an authorized transport for the same device, without restarting ADB."""
    if not physical_serial:
        return None
    if not prefer_usb and not wireless_lock.acquire(blocking=False):
        return None
    deadline = time.monotonic() + 5

    def timeout():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired("ADB recovery", 5)
        return min(2, remaining)

    def matches(target):
        return read_physical_serial(target, timeout=timeout()) == physical_serial

    try:
        with process_lock:
            other_targets = {
                state.get("serial") for state in streams.values()
                if state.get("physical_serial") != physical_serial
            }
        devices = [device for device in adb_devices(timeout=timeout())
                   if device["serial"] not in other_targets]
        usb_targets = sorted(
            (device["serial"] for device in devices if device["usb"]),
            key=lambda serial: serial != physical_serial,
        )
        if prefer_usb:
            return next((target for target in usb_targets if matches(target)), None)

        connected = [device["serial"] for device in devices
                     if not device["usb"] and ":" in device["serial"]]
        known = [target for target, record in verified_wireless.items()
                 if record["serial"] == physical_serial]
        for target in sorted(connected, key=lambda serial: serial not in known):
            if matches(target):
                remember_wireless(target, physical_serial)
                return target

        usb = next((target for target in usb_targets if matches(target)), None)
        try:
            ip = device_wifi_ip(usb, timeout=timeout()) if usb else None
        except (OSError, subprocess.TimeoutExpired):
            ip = None
        def candidates():
            if ip:
                yield f"{ip}:5555"
            yield advertised_wireless_address(physical_serial, timeout=timeout())
            yield from known

        seen = set(connected) | other_targets
        for target in candidates():
            if not target or target in seen:
                continue
            seen.add(target)
            try:
                subprocess.run([str(ADB), "connect", target],
                               capture_output=True, text=True, timeout=timeout())
            except (OSError, subprocess.TimeoutExpired):
                continue
            if matches(target):
                remember_wireless(target, physical_serial)
                return target
    except (OSError, subprocess.TimeoutExpired):
        pass
    finally:
        if not prefer_usb:
            wireless_lock.release()
    return None


def ensure_wireless(discover=True, cancelled=lambda: False):
    global preferred_wireless, wireless_error
    with wireless_lock:
        usb, wireless_devices = connection_state()
        usb = next((d["serial"] for d in adb_devices() if d.get("usb") and "Android_XR_Puck" in d.get("model", "")), None)
        if not usb or not discover:
            connected = []
            known_targets = list(dict.fromkeys([preferred_wireless, *verified_wireless]))
            with process_lock:
                active_targets = {
                    state.get("serial"): state.get("physical_serial")
                    for state in streams.values()
                    if state.get("connection") == "wireless" and state["process"].poll() is None
                }
                recovering_devices = {ticket["physical_serial"] for ticket in recoveries.values()}
            for target in dict.fromkeys([*known_targets, *wireless_devices]):
                known = verified_wireless.get(target)
                expected = known["serial"] if known else None
                if target in active_targets:
                    # A healthy video transport needs no additional ADB probe.
                    identity = active_targets[target]
                    if identity and identity != target and expected in (None, identity):
                        for address, record in list(verified_wireless.items()):
                            if address != target and record["serial"] == identity:
                                verified_wireless.pop(address, None)
                        verified_wireless[target] = {"serial": identity, "address": target}
                        connected.append(target)
                    continue
                if target not in wireless_devices:
                    continue
                try:
                    if expected is None:
                        # An already authorized transport can survive a controller restart.
                        identity = subprocess.run(
                            [str(ADB), "-s", target, "shell", "getprop", "ro.serialno"],
                            capture_output=True, text=True, timeout=3,
                        )
                        expected = identity.stdout.strip() if identity.returncode == 0 else None
                    if confirm_wireless(target, expected):
                        connected.append(target)
                except (OSError, subprocess.TimeoutExpired):
                    pass

            # One due retry per tick, oldest first; a dead headset cannot monopolize ADB.
            now = time.monotonic()
            missing = [known for target, known in verified_wireless.items()
                       if target not in connected and target not in active_targets
                       and known["serial"] not in recovering_devices
                       and wireless_retry_after.get(known["serial"], 0) <= now]
            if missing and not cancelled():
                known = min(missing, key=lambda item: wireless_retry_after.get(item["serial"], 0))
                expected = known["serial"]
                wireless_retry_after[expected] = now + 15
                target = advertised_wireless_address(expected) or known["address"]
                try:
                    if not cancelled():
                        subprocess.run([str(ADB), "connect", target],
                                       capture_output=True, text=True, timeout=3)
                        if confirm_wireless(target, expected):
                            connected.append(target)
                except (OSError, subprocess.TimeoutExpired):
                    pass
                if target not in connected:
                    wireless_error = f"Wi-Fi接続に失敗しました：{target}"
            if connected:
                preferred_wireless = preferred_wireless if preferred_wireless in connected else connected[0]
                wireless_error = ""
                return None, preferred_wireless
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
        exit_code = state["process"].poll()
        if exit_code is not None:
            finish_scrcpy(slot, state, exit_code)


def stop_scrcpy(slot):
    slot = str(slot)
    if slot not in {"1", "2", "3"}:
        raise ValueError("投影枠が正しくありません。")
    with process_lock:
        cancelled = recoveries.pop(slot, None) is not None
        state = streams.pop(slot, None)
        last_errors[slot] = ""
        if not state or state["process"].poll() is not None:
            return cancelled
        process = state["process"]
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=2)
        return True


def stop_all_scrcpy():
    with process_lock:
        slots = set(streams) | set(recoveries)
    for slot in slots:
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
    if app_id in {"maps-xr", "maps-new"}:
        import shlex
        value = 0 if app_id == "maps-new" else 1
        script = 'p=/data/user/0/com.google.android.apps.immersiveviewxr/shared_prefs/com.google.android.apps.immersiveviewxr.v2.playerprefs.xml; if test -f "$p"; then cp -p "$p" "$p.aura-guide-backup" && sed -i -E '\
            + shlex.quote('/name="(Locomotion|StreetView|Walkabout)OnboardingCompleted"/s/value="[0-9]+"/value="' + str(value) + '"/') + ' "$p"; fi'
        preparation = subprocess.run([str(ADB), "-s", serial, "shell", "am force-stop com.google.android.apps.immersiveviewxr; su 0 sh -c " + shlex.quote(script)], capture_output=True, text=True, timeout=10)
        if preparation.returncode:
            raise RuntimeError(preparation.stderr or "Maps XR の設定に失敗しました。")
    command = [str(ADB), "-s", serial, "shell", "am", "start", "-W", "-n", APPS[app_id]]
    if app_id == "maps-xr" and options.get("place") in DESTINATIONS.values():
        command += ["-a", "android.intent.action.VIEW", "-d", "geo:0,0?q=" + quote(options["place"])]
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


def open_destination(options):
    destination = options.get("destination")
    if destination == "official":
        return open_url({"target": options.get("target", "auto"), "url": "https://www.xreal.com/jp/aura"})[0]
    if destination not in DESTINATIONS:
        raise ValueError("場所が正しくありません。")
    return launch_app({"target": options.get("target", "auto"), "app": "maps-xr", "place": DESTINATIONS[destination]})


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
    allowed = {*volume_streams, "dimming", "headset-height", "full-dim", "no-dim", "passthrough-on", "immersive-on", "gemini-xr-on", "gemini-xr-off"}
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
            [str(ADB), "-s", serial, "shell", "su", "0", "cmd", "media_session", "volume", "--stream", stream, "--set", str(value)],
            capture_output=True, text=True, timeout=5,
        )
        output = f"{result.stdout}\n{result.stderr}".strip()
        if result.returncode or "Error" in output or "Exception" in output:
            raise RuntimeError(output or f"{label}を設定できません。")
        current = subprocess.run(
            [str(ADB), "-s", serial, "shell", "su", "0", "cmd", "media_session", "volume", "--stream", stream, "--get"],
            capture_output=True, text=True, timeout=5,
        )
        match = re.search(r"(?:volume is|volume:)\s*(\d+)", f"{current.stdout}\n{current.stderr}", re.I)
        if current.returncode or not match:
            raise RuntimeError(f"{label}の現在値を確認できません。")
        if int(match.group(1)) != value:
            raise RuntimeError(f"{label}を設定できませんでした（現在 {match.group(1)}/15）。")
        return serial, f"{label}：{value}"

    if action == "dimming":
        value = int(options.get("value", -1))
        if value not in range(5):
            raise ValueError("調光は0〜4段階です。")
        alpha = ("1.0", "0.7", "0.5", "0.3", "0.1")[value]
        result = subprocess.run([str(ADB), "-s", serial, "shell", "su", "0", "am", "broadcast", "-a", "com.android.systemui.xr.SET_PASSTHROUGH_DIMMING_ALPHA", "-p", "com.android.systemui", "--ef", "com.android.systemui.xr.PASSTHROUGH_OPACITY", alpha], capture_output=True, text=True, timeout=10)
        output = result.stdout + result.stderr
        if result.returncode or re.search(r"error|exception|failed", output, re.I):
            raise RuntimeError(output or "調光に失敗しました。")
        return serial, "OK"

    if action == "headset-height":
        try:
            value = float(options.get("value"))
        except (TypeError, ValueError):
            raise ValueError("高さは1.0〜2.0mで入力してください。")
        if not 1.0 <= value <= 2.0:
            raise ValueError("高さは1.0〜2.0mで入力してください。")
        subprocess.run(
            [str(ADB), "-s", serial, "shell", "su", "0", "surfaceflinger", f"--set-height-above-floor={value:.2f}"],
            capture_output=True, text=True, timeout=5, check=True,
        )
        return serial, f"眼鏡の高さ：{value:.2f}m"

    commands = {
        "full-dim": (["su", "0", "am", "broadcast", "-a", "com.android.systemui.xr.SET_PASSTHROUGH_DIMMING_ALPHA", "-p", "com.android.systemui", "--ef", "com.android.systemui.xr.PASSTHROUGH_OPACITY", "1.0"], "Full Dim を有効にしました。"),
        "no-dim": (["su", "0", "am", "broadcast", "-a", "com.android.systemui.xr.SET_PASSTHROUGH_DIMMING_ALPHA", "-p", "com.android.systemui", "--ef", "com.android.systemui.xr.PASSTHROUGH_OPACITY", "0.1"], "No Dim を有効にしました。"),
        "passthrough-on": (["su", "0", "am", "broadcast", "-a", "ix.moohan.sysui.TOGGLE_PASSTHROUGH", "--es", "enable", "true"], "Passthrough ON にしました。"),
        "immersive-on": (["su", "0", "am", "broadcast", "-a", "ix.moohan.sysui.TOGGLE_PASSTHROUGH", "--es", "enable", "false"], "Immersive ON にしました。"),
        "gemini-xr-on": (["su", "0", "am", "start", "-n", "com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.xr.main.XrModeActivity", "-f", "0x10000000"], "Gemini XR を起動しました。"),
        "gemini-xr-off": (["am", "force-stop", "com.google.android.googlequicksearchbox"], "Gemini XR を停止しました。"),
    }
    command, message = commands[action]
    result = subprocess.run([str(ADB), "-s", serial, "shell", *command], capture_output=True, text=True, timeout=10, check=True)
    if re.search(r"error|exception|failed", result.stdout + result.stderr, re.I):
        raise RuntimeError("操作に失敗しました。")
    if action == "gemini-xr-on":
        confirm_app_task(serial, GEMINI_PACKAGE)
    return serial, message


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
    allowed = {"close-current", "close-all", "back", "show-launcher", "open-workset", "arrange", "recenter"}
    if action not in allowed:
        raise ValueError("操作が正しくありません。")
    serial = resolve_target(str(options.get("target", "auto")))
    if not serial:
        raise RuntimeError("接続可能なデバイスが見つかりません。")

    if action == "back":
        subprocess.run([str(ADB), "-s", serial, "shell", "input", "keyevent", "KEYCODE_BACK"], timeout=5)
        return serial, "戻りました。"
    if action in {"arrange", "recenter"}:
        shortcut = "TIDY_UP_PANELS" if action == "arrange" else "RECENTER"
        subprocess.run([str(ADB), "-s", serial, "shell", "su", "0", "am", "broadcast", "-a", "ix.moohan.sysui.KEYBOARD_SHORTCUT", "--es", "SHORTCUT", shortcut], capture_output=True, text=True, timeout=5, check=True)
        return serial, "OK"
    if action == "show-launcher":
        subprocess.run(
            [str(ADB), "-s", serial, "shell", "su", "0", "am", "broadcast", "-a", "SYSTEM_INTENT_ACTION_LAUNCHER"],
            capture_output=True, text=True, timeout=5, check=True,
        )
        return serial, "Launcherを表示しました。"
    if action == "open-workset":
        for launch_args in GROUP_APPS:
            result = subprocess.run([str(ADB), "-s", serial, "shell", "am", "start", "-W", *launch_args], capture_output=True, text=True, timeout=15, check=True)
            output = f"{result.stdout}\n{result.stderr}".strip()
            if "Error:" in output or "Exception" in output:
                raise RuntimeError(output or "アプリを起動できません。")
            confirm_app_task(serial, launch_args[-1].split("/", 1)[0])
        return serial, "OK"
    if action == "close-all":
        result = subprocess.run(
            [str(ADB), "-s", serial, "shell", "am", "stack", "list"],
            capture_output=True, text=True, timeout=8, check=True,
        )
        stacks = result.stdout
        if re.search(r"error|exception|failed", stacks + result.stderr, re.I):
            raise RuntimeError("アプリを閉じられませんでした。")
        packages = sorted(set(re.findall(r"taskId=\d+:\s+([\w.]+)/", stacks)) - {"com.android.systemui"})
        for package in packages:
            closed = subprocess.run([str(ADB), "-s", serial, "shell", "am", "stop-app", "--user", "current", package], capture_output=True, text=True, timeout=5, check=True)
            if re.search(r"error|exception|failed", closed.stdout + closed.stderr, re.I):
                raise RuntimeError("アプリを閉じられませんでした。")
        return serial, f"{len(packages)} 個のアプリを閉じました。"

    task = current_task(serial)
    if not task or task[1] == "com.android.systemui":
        raise RuntimeError("操作できるアプリウィンドウがありません。")
    task_id, package, mode = task
    if action == "close-current":
        subprocess.run([str(ADB), "-s", serial, "shell", "am", "stop-app", "--user", "current", package], timeout=5, check=True)
        return serial, f"{package} を閉じました。"


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


def spawn_scrcpy(state):
    with state["log_path"].open("ab") as log:
        state["log_offset"] = log.tell()
        state["process"] = subprocess.Popen(
            state["command"], cwd=ROOT, env=state["env"],
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
        )
    state["started_at"] = time.monotonic()


def finish_scrcpy(slot, state, exit_code):
    streams.pop(slot, None)
    wireless = state.get("connection") == "wireless" and state.get("ready") and state.get("options")
    if exit_code == 0 and not wireless:
        last_errors[slot] = ""
        return False
    try:
        with state["log_path"].open("rb") as log:
            end = log.seek(0, os.SEEK_END)
            log.seek(max(state["log_offset"], end - 4096))
            tail = log.read(4096).decode(errors="replace").strip()
    except (KeyError, OSError):
        tail = ""
    if wireless and exit_code >= 0 and (exit_code > 0 or "Device disconnected" in tail):
        now = time.monotonic()
        previous = state.get("recovery")
        # A Wi-Fi retry that fails again within seconds must not reset the USB deadline.
        if previous and now - state["started_at"] < WIFI_RECOVERY_STABLE:
            recoveries[slot] = previous
        else:
            recoveries[slot] = {
                "physical_serial": state["physical_serial"],
                "options": state["options"], "usb_after": now + WIFI_RECOVERY_GRACE,
                "next_attempt": now,
            }
    if exit_code == 0 and slot not in recoveries:
        last_errors[slot] = ""
        return False
    last_errors[slot] = tail[-1200:] or f"scrcpy が終了しました（終了コード {exit_code}）。"
    if not (
        exit_code > 0 and state.get("connection") == "usb"
        and state.get("audio") not in (None, "no-audio") and not state.get("record")
        and "Could not open audio device: CoreAudio error (AudioQueueStart)" in tail
    ):
        return False
    try:
        if not any(item["usb"] and item["serial"] == state["serial"] for item in adb_devices()):
            return False
        # A failed Mac audio output must not tear down an otherwise usable USB display.
        state["command"] = [arg for arg in state["command"] if not arg.startswith("--audio-")] + ["--no-audio"]
        state["audio"] = "no-audio"
        spawn_scrcpy(state)
    except (OSError, subprocess.SubprocessError):
        return False
    streams[slot] = state
    last_errors[slot] = "Macの音声出力を開けなかったため、USB映像のみで再開しました。"
    return True


def projection_recovery_step():
    with process_lock:
        cleanup_streams()
        pending = list(recoveries.items())
    for slot, ticket in pending:
        now = time.monotonic()
        if monitor_stop.is_set() or now < ticket["next_attempt"]:
            continue
        with process_lock:
            if recoveries.get(slot) is not ticket or slot in streams:
                continue
            usb_due = now >= ticket["usb_after"]
            ticket["next_attempt"] = now + (10 if usb_due else 3)
        for prefer_usb in ((True, False) if usb_due else (False,)):
            try:
                target = recovery_target(ticket["physical_serial"], prefer_usb=prefer_usb)
                with process_lock:
                    if recoveries.get(slot) is not ticket or slot in streams:
                        break
                if target:
                    start_scrcpy({**ticket["options"], "slot": slot, "target": target}, recovery=ticket)
                    break
            except (OSError, RuntimeError, subprocess.SubprocessError):
                # A vanished endpoint or failed encoder is retried on the next due tick.
                continue


def monitor_projection_recovery():
    while not monitor_stop.is_set():
        projection_recovery_step()
        monitor_stop.wait(2)


def start_scrcpy(options, *, recovery=None):
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
    settings = projection_settings(options, connection)
    resolution = settings["resolution"]
    audio = settings["audio"]
    physical_result = subprocess.run(
        [str(ADB), "-s", serial, "shell", "getprop", "ro.serialno"],
        capture_output=True, text=True, timeout=5,
    )
    reported_serial = physical_result.stdout.strip() if physical_result.returncode == 0 else ""
    expected = recovery["physical_serial"] if recovery is not None else options.get("physical_serial")
    if expected is not None and reported_serial != expected:
        raise RuntimeError("選択したデバイスのシリアル番号が一致しません。")
    physical_serial = reported_serial or serial
    display_id = internal_display_id(serial)
    projection_env = scrcpy_environment()

    with process_lock:
        cleanup_streams()
        current = streams.get(slot)
        if recovery is not None and (recoveries.get(slot) is not recovery or current or monitor_stop.is_set()):
            raise RuntimeError("投影の再接続はキャンセルされました。")
        if any(
            state["physical_serial"] == physical_serial
            for other_slot, state in streams.items() if other_slot != slot
        ):
            raise RuntimeError("このデバイスは別の投影枠で使用中です。")

        audio_adjusted = False
        if audio != "no-audio" and any(
            state["audio"] != "no-audio"
            for other_slot, state in streams.items() if other_slot != slot
        ):
            audio = "no-audio"
            audio_adjusted = True

        config = (serial, physical_serial, display_id, tuple(sorted(settings.items())), audio,
                  projection_label, bool(options.get("always_top", False)),
                  bool(options.get("mouse", False)), bool(options.get("record", False)))
        if current and current.get("config") == config:
            # Repeated Start must not disconnect an unchanged live stream or recording.
            return serial, connection, None, current["audio"], audio_adjusted
        if recovery is None:
            for other_slot, ticket in list(recoveries.items()):
                if other_slot == slot or ticket["physical_serial"] == physical_serial:
                    recoveries.pop(other_slot, None)
        if current:
            process = current["process"]
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
            streams.pop(slot, None)

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

        state = {
            "serial": serial, "physical_serial": physical_serial, "connection": connection,
            "audio": audio, "resolution": resolution, "frame_rate": settings["frame_rate"],
            "config": config, "record": bool(options.get("record", False)),
            "options": dict(options) if reported_serial else None, "recovery": recovery,
            "command": command, "env": projection_env, "log_path": DATA_ROOT / f"scrcpy-{slot}.log",
        }
        spawn_scrcpy(state)
        streams[slot] = state
        last_errors[slot] = ""
        while True:
            try:
                exit_code = state["process"].wait(timeout=1)
            except subprocess.TimeoutExpired:
                state["ready"] = True
                if recovery is not None:
                    recoveries.pop(slot, None)
                return serial, connection, state["command"], state["audio"], audio_adjusted
            if not finish_scrcpy(slot, state, exit_code):
                raise RuntimeError(last_errors[slot] or "scrcpy が終了しました（終了コード 0）。")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, format, *args):
        pass

    def end_headers(self):
        if urlsplit(self.path).path in PAGE_ASSETS or self.path == "/api/version":
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def send_head(self):
        if urlsplit(self.path).path in PAGE_ASSETS:
            # A newly installed build may carry older file timestamps than a cached copy.
            del self.headers["If-Modified-Since"]
        return super().send_head()

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/version":
            with process_lock:
                projection_running = bool(streams or recoveries)
            self.send_json(200, {
                "version": VERSION,
                "page_version": (ROOT / "VERSION").read_text().strip(),
                "projection_running": projection_running,
            })
            return
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
                    projection_status = {}
                    for slot in ("1", "2", "3"):
                        stream = streams.get(slot, {})
                        pending = recoveries.get(slot, {})
                        projection_status[slot] = {
                            "running": bool(stream),
                            "recovering": bool(pending),
                            "physical_serial": stream.get("physical_serial") or pending.get("physical_serial"),
                            "active_serial": stream.get("serial"),
                            "active_connection": stream.get("connection"),
                            "audio": stream.get("audio"),
                            "resolution": stream.get("resolution"),
                            "frame_rate": stream.get("frame_rate"),
                            "error": last_errors[slot],
                        }
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
                    "streams": projection_status,
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
            if self.path in {"/api/launch", "/api/destination", "/api/tune", "/api/open-url", "/api/control"} and "physical_serial" in data:
                target = resolve_target(str(data.get("target", "")))
                expected = data["physical_serial"]
                if not isinstance(expected, str) or not expected or read_physical_serial(target) != expected:
                    raise RuntimeError("選択したデバイスのシリアル番号が一致しません。")
                data["target"] = target

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
            elif self.path == "/api/destination":
                self.send_json(200, {"success": True, "serial": open_destination(data)})
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
    threading.Thread(target=monitor_projection_recovery, daemon=True).start()
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
