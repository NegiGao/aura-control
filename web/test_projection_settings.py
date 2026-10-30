"""Offline regression checks: no real device or scrcpy process is touched."""
import json
import subprocess
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import Mock, patch

import server


class ProjectionSettingsTest(unittest.TestCase):
    def test_wireless_defaults_and_all_choices(self):
        self.assertEqual(server.projection_settings({}, "wireless")["frame_rate"], "30")
        self.assertEqual(server.projection_settings({}, "wireless")["resolution"], "1280")
        for size in ("1280", "1600", "1920"):
            for fps in ("30", "60"):
                settings = server.projection_settings({"resolution": size, "frame_rate": fps}, "wireless")
                self.assertEqual((settings["resolution"], settings["frame_rate"]), (size, fps))

    def test_invalid_wireless_settings(self):
        for options in ({"frame_rate": "120"}, {"resolution": "0"}, {"audio": "invalid"}):
            with self.assertRaises(ValueError):
                server.projection_settings(options, "wireless")

    def test_usb_forces_highest_even_with_low_client_values(self):
        settings = server.projection_settings({"resolution": "1280", "frame_rate": "30", "audio": "no-audio"}, "usb")
        self.assertEqual(settings, {"resolution": "0", "frame_rate": "60", "audio": "both",
                                    "video_bit_rate": "20M", "audio_bit_rate": "256k"})

    def test_wireless_recording_uses_highest_and_preserves_audio_choice(self):
        for audio in ("no-audio", "playback", "output", "both"):
            settings = server.projection_settings({"resolution": "1280", "frame_rate": "30",
                                                   "audio": audio, "record": True}, "wireless")
            self.assertEqual(settings, {"resolution": "0", "frame_rate": "60", "audio": audio,
                                        "video_bit_rate": "20M", "audio_bit_rate": "256k"})

    def test_recording_does_not_bypass_wireless_input_validation(self):
        with self.assertRaises(ValueError):
            server.projection_settings({"resolution": "invalid", "record": True}, "wireless")

    def test_command_uses_resolved_transport_and_preserves_pc_audio_exclusion(self):
        for serial, audio_busy in (("USB-TEST", False), ("USB-TEST", True), ("192.0.2.1:5555", False)):
            with self.subTest(serial=serial, audio_busy=audio_busy), tempfile.TemporaryDirectory() as directory:
                process = Mock()
                process.wait.side_effect = subprocess.TimeoutExpired("scrcpy", 1)
                others = {"2": {"audio": "both", "physical_serial": "OTHER"}} if audio_busy else {}
                with patch.object(server, "streams", others), patch.object(server, "cleanup_streams"), \
                     patch.object(server, "DATA_ROOT", Path(directory)), patch.object(server, "RECORDINGS", Path(directory) / "recordings"), \
                     patch.object(server, "resolve_target", return_value=serial), patch.object(server, "internal_display_id", return_value="4"), \
                     patch.object(server, "quarter_screen_size", return_value=(960, 600)), patch.object(server, "scrcpy_environment", return_value={}), \
                     patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "TEST-PHYSICAL", "")), \
                     patch.object(server.subprocess, "Popen", return_value=process):
                    _, connection, command, audio, adjusted = server.start_scrcpy({
                        "slot": "1", "target": "client-choice", "resolution": "1280",
                        "frame_rate": "30", "audio": "both", "record": True,
                        "language": "en",
                    })
                    self.assertIn("--display-id=4", command)
                    self.assertIn("--window-width=960", command)
                    self.assertTrue(any(item.startswith("--window-title=Casting 1") for item in command))
                    self.assertTrue(any(item.startswith("--record=") and item.endswith(".mp4") for item in command))
                    recording_path = Path(next(item.split("=", 1)[1] for item in command if item.startswith("--record=")))
                    self.assertEqual(recording_path.parent, server.RECORDINGS)
                    self.assertFalse((server.RECORDINGS / "originals").exists())
                    self.assertNotIn("recording_job", server.streams["1"])
                    self.assertIn("--record-format=mp4", command)
                    self.assertIn("--video-codec=h264", command)
                    self.assertIn("--max-size=0", command)
                    self.assertIn("--max-fps=60", command)
                    self.assertIn("--video-bit-rate=20M", command)
                    if not audio_busy:
                        self.assertIn("--audio-codec=aac", command)
                        self.assertIn("--audio-bit-rate=256k", command)
                    if connection == "usb":
                        self.assertIn("--max-size=0", command)
                        self.assertIn("--max-fps=60", command)
                        self.assertIn("--video-bit-rate=20M", command)
                        if not audio_busy:
                            self.assertIn("--audio-bit-rate=256k", command)
                            self.assertIn("--audio-dup", command)
                    self.assertEqual(adjusted, audio_busy)
                    if audio_busy:
                        self.assertEqual(audio, "no-audio")
                        self.assertIn("--no-audio", command)

    def test_invalid_language_is_rejected_before_any_device_access(self):
        with patch.object(server, "resolve_target", side_effect=AssertionError("must not touch a device")):
            with self.assertRaises(ValueError):
                server.start_scrcpy({"slot": "1", "language": "invalid"})

    def test_folder_endpoint_needs_no_device_and_uses_server_path(self):
        http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        worker = threading.Thread(target=http.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory, \
                 patch.object(server, "RECORDINGS", Path(directory) / "recordings"), \
                 patch.object(server, "resolve_target", side_effect=AssertionError("must not need a device")), \
                 patch.object(server.subprocess, "run") as opened:
                url = f"http://127.0.0.1:{http.server_port}/api/open-recordings"
                request = urllib.request.Request(url, data=b'{"path":"/ignored-client-path"}', method="POST")
                with urllib.request.urlopen(request) as response:
                    result = json.load(response)
                self.assertTrue(result["success"])
                self.assertEqual(result["path"], str(Path(directory) / "recordings"))
                opened.assert_called_once_with(["/usr/bin/open", result["path"]], check=True,
                                               capture_output=True, text=True, timeout=10)
                self.assertTrue(Path(result["path"]).is_dir())
        finally:
            http.shutdown()
            http.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
