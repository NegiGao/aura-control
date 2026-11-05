"""Exercise USB audio fallback without connecting to a device."""
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

import server


COREAUDIO = b"ERROR: Could not open audio device: CoreAudio error (AudioQueueStart): 1937010544\n"


class UsbCoreAudioFallbackTest(unittest.TestCase):
    def setUp(self):
        stack = ExitStack()
        self.addCleanup(stack.close)
        directory = Path(stack.enter_context(tempfile.TemporaryDirectory()))
        self.log = directory / "scrcpy-1.log"
        for name, value in (
            ("streams", {}), ("last_errors", {"1": ""}),
            ("DATA_ROOT", directory), ("RECORDINGS", directory / "recordings"),
        ):
            stack.enter_context(patch.object(server, name, value))
        self.target = stack.enter_context(patch.object(server, "resolve_target", return_value="USB-TEST"))
        for name, value in (
            ("internal_display_id", "1"), ("quarter_screen_size", (960, 540)),
            ("scrcpy_environment", {}), ("adb_devices", [{"usb": True, "serial": "USB-TEST"}]),
        ):
            stack.enter_context(patch.object(server, name, return_value=value))
        stack.enter_context(patch.object(server.subprocess, "run", return_value=
                            subprocess.CompletedProcess([], 0, "USB-TEST", "")))
        self.spawned = stack.enter_context(patch.object(server.subprocess, "Popen", side_effect=self.spawn))
        self.processes = []
        self.fail_at_start = False
        self.options = {"slot": "1", "target": "USB-TEST", "always_top": True}

    def spawn(self, command, *, stdout, **kwargs):
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("scrcpy", 1)
        if self.fail_at_start and not self.processes:
            stdout.write(COREAUDIO)
            stdout.flush()
            process.wait.side_effect = None
            process.wait.return_value = 2
        self.processes.append(process)
        return process

    def fail_after_start(self, error=COREAUDIO):
        with self.log.open("ab") as log:
            log.write(error)
        self.processes[-1].poll.return_value = 2

    def assert_video_only_retry(self):
        self.assertEqual(self.spawned.call_count, 2)
        original, retry = (call.args[0] for call in self.spawned.call_args_list)
        self.assertIn("--audio-dup", original)
        self.assertEqual(retry, [arg for arg in original if not arg.startswith("--audio-")] + ["--no-audio"])
        self.assertEqual(server.streams["1"]["audio"], "no-audio")
        self.assertIn("USB映像のみ", server.last_errors["1"])

    def test_startup_failure_retries_and_returns_actual_audio(self):
        self.fail_at_start = True
        result = server.start_scrcpy(self.options)
        self.assertEqual(result[3], "no-audio")
        self.assert_video_only_retry()
        self.assertEqual(server.start_scrcpy(self.options)[2:4], (None, "no-audio"))
        self.assertEqual(self.spawned.call_count, 2)

    def test_delayed_failure_retries_at_cleanup_only_once(self):
        server.start_scrcpy(self.options)
        self.fail_after_start()
        server.cleanup_streams()
        self.assert_video_only_retry()
        self.fail_after_start()
        server.cleanup_streams()
        self.assertFalse(server.streams)
        self.assertEqual(self.spawned.call_count, 2)

    def test_stale_error_does_not_replay(self):
        self.log.write_bytes(COREAUDIO)
        server.start_scrcpy(self.options)
        self.fail_after_start(b"ERROR: Device disconnected\n")
        server.cleanup_streams()
        self.assertFalse(server.streams)
        self.assertNotIn("CoreAudio", server.last_errors["1"])
        self.spawned.assert_called_once()

    def test_recording_and_wireless_do_not_retry(self):
        self.fail_at_start = True
        with self.assertRaisesRegex(RuntimeError, "AudioQueueStart"):
            server.start_scrcpy({**self.options, "record": True})
        self.spawned.assert_called_once()
        self.spawned.reset_mock()
        self.processes.clear()
        self.target.return_value = "192.0.2.1:5555"
        with self.assertRaisesRegex(RuntimeError, "AudioQueueStart"):
            server.start_scrcpy(self.options)
        self.spawned.assert_called_once()

    def test_disconnected_or_changed_usb_target_is_not_replaced(self):
        server.start_scrcpy(self.options)
        self.fail_after_start()
        with patch.object(server, "adb_devices", return_value=[{"usb": True, "serial": "OTHER"}]):
            server.cleanup_streams()
        self.assertFalse(server.streams)
        self.spawned.assert_called_once()

    def test_stop_never_retries_an_already_failed_process(self):
        server.start_scrcpy(self.options)
        self.fail_after_start()
        self.assertFalse(server.stop_scrcpy("1"))
        server.cleanup_streams()
        self.assertFalse(server.streams)
        self.spawned.assert_called_once()


if __name__ == "__main__":
    unittest.main()
