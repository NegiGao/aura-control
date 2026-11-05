"""Exercise wireless projection recovery without running ADB or scrcpy."""
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, call, patch

import server


OLD_WIFI = "192.0.2.11:5555"
NEW_WIFI = "192.0.2.12:5555"
USB = "TEST-AURA"


class ProjectionRecoveryTest(unittest.TestCase):
    def setUp(self):
        stack = ExitStack()
        self.addCleanup(stack.close)
        directory = Path(stack.enter_context(tempfile.TemporaryDirectory()))
        self.log = directory / "scrcpy-1.log"
        for name, value in (
            ("streams", {}), ("recoveries", {}), ("last_errors", {"1": ""}),
            ("DATA_ROOT", directory), ("RECORDINGS", directory / "recordings"),
        ):
            stack.enter_context(patch.object(server, name, value))
        self.clock = stack.enter_context(patch.object(server.time, "monotonic", return_value=100))
        stack.enter_context(patch.object(server, "monitor_wake", Mock()))
        stack.enter_context(patch.object(server, "resolve_target", side_effect=lambda serial: serial))
        self.display = stack.enter_context(patch.object(server, "internal_display_id", return_value="1"))
        stack.enter_context(patch.object(server, "quarter_screen_size", return_value=(960, 600)))
        stack.enter_context(patch.object(server, "scrcpy_environment", return_value={}))
        self.identity = stack.enter_context(patch.object(server.subprocess, "run", return_value=
                             subprocess.CompletedProcess([], 0, USB, "")))
        self.lookup = stack.enter_context(patch.object(server, "recovery_target"))
        self.spawned = stack.enter_context(patch.object(server.subprocess, "Popen", side_effect=self.spawn))
        self.kill = stack.enter_context(patch.object(server.os, "killpg"))
        self.options = {
            "slot": "1", "target": OLD_WIFI, "language": "zh", "resolution": "1600",
            "frame_rate": "60", "audio": "no-audio", "always_top": True, "mouse": True,
        }

    def spawn(self, command, **kwargs):
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("scrcpy", 1)
        return process

    def start(self, **options):
        return server.start_scrcpy({**self.options, **options})

    def lose_stream(self, exit_code=1, log=b"WARN: Device disconnected\n"):
        with self.log.open("ab") as handle:
            handle.write(log)
        server.streams["1"]["process"].poll.return_value = exit_code
        server.cleanup_streams()

    def queued(self):
        self.start()
        self.lose_stream()
        self.assertNotIn("1", server.streams)
        self.assertIn("1", server.recoveries)

    def test_same_aura_new_wifi_ip_reuses_the_slot_and_profile(self):
        self.queued()
        self.lookup.return_value = NEW_WIFI
        server.projection_recovery_step()
        server.projection_recovery_step()

        self.lookup.assert_called_once_with(USB, prefer_usb=False)
        self.assertEqual(server.streams["1"]["serial"], NEW_WIFI)
        self.assertNotIn("1", server.recoveries)
        self.assertEqual(self.spawned.call_count, 2)
        self.kill.assert_not_called()
        command = self.spawned.call_args.args[0]
        for flag in ("--max-size=1600", "--max-fps=60", "--always-on-top",
                     "--mouse=uhid", "--no-audio"):
            self.assertIn(flag, command)
        self.assertTrue(any(item.startswith("--window-title=投屏 1") for item in command))

    def test_worker_detects_an_exit_without_any_browser_request(self):
        self.start()
        self.lookup.return_value = NEW_WIFI
        server.projection_recovery_step()
        self.lookup.assert_not_called()

        server.streams["1"]["process"].poll.return_value = 1
        server.projection_recovery_step()
        self.assertEqual(server.streams["1"]["serial"], NEW_WIFI)

    def test_expired_wifi_grace_uses_same_aura_usb_and_stays_on_it(self):
        self.queued()
        self.clock.return_value += server.WIFI_RECOVERY_GRACE + 1
        self.lookup.return_value = USB
        server.projection_recovery_step()
        server.projection_recovery_step()

        self.lookup.assert_called_once_with(USB, prefer_usb=True)
        self.assertEqual(server.streams["1"]["connection"], "usb")
        self.assertEqual(self.spawned.call_count, 2)
        self.assertNotIn("1", server.recoveries)

    def test_after_grace_without_usb_can_still_recover_wifi(self):
        self.queued()
        self.clock.return_value += server.WIFI_RECOVERY_GRACE + 1
        self.lookup.side_effect = [None, NEW_WIFI]
        server.projection_recovery_step()

        self.assertEqual(self.lookup.call_args_list,
                         [call(USB, prefer_usb=True), call(USB, prefer_usb=False)])
        self.assertEqual(server.streams["1"]["serial"], NEW_WIFI)

    def test_after_grace_failed_usb_start_can_try_wifi_on_same_tick(self):
        self.queued()
        self.clock.return_value += server.WIFI_RECOVERY_GRACE + 1
        self.lookup.side_effect = [USB, NEW_WIFI]

        def fail_usb(command, **kwargs):
            process = self.spawn(command, **kwargs)
            if command[2] == USB:
                process.wait.side_effect = None
                process.wait.return_value = 1
            return process

        self.spawned.side_effect = fail_usb
        server.projection_recovery_step()

        self.assertEqual(self.lookup.call_args_list,
                         [call(USB, prefer_usb=True), call(USB, prefer_usb=False)])
        self.assertEqual(server.streams["1"]["serial"], NEW_WIFI)
        self.assertNotIn("1", server.recoveries)
        self.assertEqual(self.spawned.call_count, 3)

    def test_clean_window_close_does_not_reopen_but_logged_disconnect_does(self):
        self.start()
        self.lose_stream(exit_code=0, log=b"")
        server.projection_recovery_step()
        self.lookup.assert_not_called()
        self.assertFalse(server.recoveries)

        self.start()
        self.lose_stream(exit_code=0)
        self.assertIn("1", server.recoveries)

    def test_stop_while_network_lookup_is_in_progress_cancels_recovery(self):
        self.queued()

        def stop_during_lookup(*args, **kwargs):
            server.stop_scrcpy("1")
            return NEW_WIFI

        self.lookup.side_effect = stop_during_lookup
        server.projection_recovery_step()
        server.projection_recovery_step()
        self.spawned.assert_called_once()
        self.kill.assert_not_called()
        self.assertFalse(server.recoveries)
        self.assertFalse(server.streams)

    def test_manual_start_while_network_lookup_is_in_progress_wins(self):
        self.queued()

        def start_during_lookup(*args, **kwargs):
            self.start(target=USB)
            return NEW_WIFI

        self.lookup.side_effect = start_during_lookup
        server.projection_recovery_step()

        self.assertEqual(server.streams["1"]["serial"], USB)
        self.assertNotIn("1", server.recoveries)
        self.assertEqual(self.spawned.call_count, 2)
        self.kill.assert_not_called()

    def test_recovery_rechecks_identity_before_display_access(self):
        self.queued()
        self.lookup.return_value = NEW_WIFI
        self.identity.return_value = subprocess.CompletedProcess([], 0, "WRONG-DEVICE", "")
        self.display.reset_mock()
        server.projection_recovery_step()

        self.display.assert_not_called()
        self.spawned.assert_called_once()
        self.assertIn("1", server.recoveries)

    def test_failed_start_retries_are_throttled_and_do_not_reset_usb_deadline(self):
        self.queued()
        self.lookup.side_effect = [NEW_WIFI, USB]
        self.display.side_effect = [RuntimeError("display not ready"), "1"]
        server.projection_recovery_step()
        server.projection_recovery_step()
        self.lookup.assert_called_once_with(USB, prefer_usb=False)
        self.assertIn("1", server.recoveries)

        self.clock.return_value += server.WIFI_RECOVERY_GRACE + 1
        server.projection_recovery_step()
        self.assertEqual(self.lookup.call_args_list,
                         [call(USB, prefer_usb=False), call(USB, prefer_usb=True)])
        self.assertEqual(server.streams["1"]["serial"], USB)

    def test_wifi_reopen_that_dies_quickly_does_not_reset_usb_deadline(self):
        self.queued()
        self.clock.return_value += server.WIFI_RECOVERY_GRACE - 4
        self.lookup.side_effect = [NEW_WIFI, USB]
        server.projection_recovery_step()
        self.clock.return_value += 2
        self.lose_stream()
        self.clock.return_value += 3
        server.projection_recovery_step()
        self.assertEqual(self.lookup.call_args_list,
                         [call(USB, prefer_usb=False), call(USB, prefer_usb=True)])
        self.assertEqual(server.streams["1"]["serial"], USB)

    def test_recovering_a_recording_starts_a_new_segment(self):
        self.start(record=True)
        self.lose_stream()
        self.lookup.return_value = NEW_WIFI
        server.projection_recovery_step()

        files = [next(arg for arg in item.args[0] if arg.startswith("--record="))
                 for item in self.spawned.call_args_list]
        self.assertEqual(len(files), 2)
        self.assertNotEqual(*files)
        self.assertTrue(server.streams["1"]["record"])

    def test_control_rejects_reused_ip_before_dispatch(self):
        body = json.dumps({"target": OLD_WIFI, "physical_serial": USB, "action": "back"}).encode()
        handler = object.__new__(server.Handler)
        handler.path = "/api/control"
        handler.headers = {"Content-Length": str(len(body))}
        handler.rfile = io.BytesIO(body)
        handler.send_json = Mock()
        self.identity.return_value = subprocess.CompletedProcess([], 0, "WRONG-DEVICE", "")

        with patch.object(server, "control_device") as control:
            handler.do_POST()
        control.assert_not_called()
        status, payload = handler.send_json.call_args.args
        self.assertEqual(status, 400)
        self.assertIn("シリアル番号", payload["error"])


if __name__ == "__main__":
    unittest.main()
