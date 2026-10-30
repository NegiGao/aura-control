"""Device-free checks for discovery control and confirmed app actions."""
import subprocess
import unittest
from unittest.mock import patch

import server


class ExperienceControlsTest(unittest.TestCase):
    def test_workset_launches_five_apps_on_only_the_selected_device(self):
        packages = ["com.google.android.gm", "com.google.android.apps.docs.editors.docs",
                    "com.google.android.apps.youtube.xr", "com.google.android.apps.photos", "com.google.android.apps.maps"]
        with patch.object(server, "resolve_target", return_value="USB-SELECTED"), \
             patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Status: ok", "")) as run, \
             patch.object(server, "confirm_app_task") as confirmed:
            self.assertEqual(server.control_device({"action": "open-workset", "target": "USB-SELECTED"}), ("USB-SELECTED", "OK"))
            self.assertEqual(run.call_count, 5)
            for call, package in zip(run.call_args_list, packages):
                self.assertEqual(call.args[0][:7], [str(server.ADB), "-s", "USB-SELECTED", "shell", "am", "start", "-W"])
                self.assertIn(package, call.args[0][-1])
            self.assertEqual([call.args for call in confirmed.call_args_list], [("USB-SELECTED", package) for package in packages])

    def test_workset_stops_and_reports_an_android_launcher_error(self):
        with patch.object(server, "resolve_target", return_value="USB"), \
             patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Error: missing app", "")), \
             patch.object(server, "confirm_app_task") as confirmed:
            with self.assertRaises(RuntimeError):
                server.control_device({"action": "open-workset"})
            confirmed.assert_not_called()

    def setUp(self):
        state = {"automatic": False, "pending": False, "running": False, "generation": 0}
        self.state = patch.object(server, "discovery", state)
        self.state.start()
        self.addCleanup(self.state.stop)

    def test_discovery_defaults_off_but_recovery_continues(self):
        with patch.object(server, "ensure_wireless", return_value=(None, None)) as ensure:
            server.wireless_monitor_step()
            self.assertFalse(ensure.call_args.kwargs["discover"])
        self.assertEqual(server.discovery_status(), {"automatic": False, "pending": False, "running": False})

    def test_success_stops_auto_discovery_without_disconnect(self):
        server.configure_discovery({"automatic": True})
        with patch.object(server, "ensure_wireless", return_value=(None, "192.0.2.1:5555")) as ensure:
            server.wireless_monitor_step()
            self.assertTrue(ensure.call_args.kwargs["discover"])
        self.assertFalse(server.discovery_status()["automatic"])

    def test_one_scan_is_not_repeated_after_failure(self):
        server.configure_discovery({"once": True})
        with patch.object(server, "ensure_wireless", return_value=(None, None)) as ensure:
            server.wireless_monitor_step()
            self.assertTrue(ensure.call_args.kwargs["discover"])
            server.wireless_monitor_step()
            self.assertFalse(ensure.call_args.kwargs["discover"])

    def test_off_invalidates_inflight_scan(self):
        server.configure_discovery({"automatic": True})
        def during_scan(**kwargs):
            self.assertFalse(kwargs["cancelled"]())
            server.configure_discovery({"automatic": False})
            self.assertTrue(kwargs["cancelled"]())
            return None, None
        with patch.object(server, "ensure_wireless", side_effect=during_scan):
            server.wireless_monitor_step()
        self.assertFalse(server.discovery_status()["automatic"])

    def test_scan_failure_clears_running_state(self):
        server.configure_discovery({"once": True})
        with patch.object(server, "ensure_wireless", side_effect=RuntimeError("offline")):
            with self.assertRaises(RuntimeError):
                server.wireless_monitor_step()
        self.assertFalse(server.discovery_status()["running"])

    def test_invalid_settings_are_rejected(self):
        for value in ({}, {"automatic": "true"}, {"once": 1}):
            with self.assertRaises(ValueError):
                server.configure_discovery(value)

    def test_discovery_off_does_not_bootstrap_wired_device(self):
        with patch.object(server, "connection_state", return_value=("USB", [])), \
             patch.object(server, "adb_devices", return_value=[{"serial":"USB", "usb":True, "model":"Android_XR_Puck"}]), \
             patch.object(server, "verified_wireless", {}), \
             patch.object(server, "preferred_wireless", None), \
             patch.object(server.subprocess, "run", side_effect=AssertionError("no device mutations")):
            self.assertEqual(server.ensure_wireless(discover=False), (None, None))

    def test_launch_failure_does_not_claim_confirmed_activity(self):
        with patch.object(server, "resolve_target", return_value="USB"), \
             patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Error: missing activity", "")), \
             patch.object(server, "confirm_app_task") as confirmed:
            with self.assertRaises(RuntimeError):
                server.launch_app({"app":"youtube"})
            confirmed.assert_not_called()

    def test_launch_checks_target_activity(self):
        with patch.object(server, "resolve_target", return_value="USB"), \
             patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Status: ok", "")), \
             patch.object(server, "confirm_app_task") as confirmed:
            self.assertEqual(server.launch_app({"app":"youtube"}), "USB")
            confirmed.assert_called_once_with("USB", "com.google.android.apps.youtube.xr")

    def test_activity_confirmation_rejects_other_package(self):
        for output, success in (("mResumedActivity: ActivityRecord{a u0 com.example.demo/.Main t42}", True),
                                ("ActivityRecord{a u0 com.example.other/.Main t42}", False)):
            with patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output, "")), patch.object(server.time, "sleep"):
                if success:
                    server.confirm_app_task("USB", "com.example.demo")
                else:
                    with self.assertRaises(RuntimeError):
                        server.confirm_app_task("USB", "com.example.demo")

    def test_only_allowlisted_landmarks_are_launched(self):
        with patch.object(server, "launch_app", return_value="USB") as launch:
            self.assertEqual(server.open_destination({"target":"USB", "destination":"skytree"}), "USB")
            launch.assert_called_once_with({"target":"USB", "app":"maps-xr", "place":"東京スカイツリー"})
            with self.assertRaises(ValueError):
                server.open_destination({"destination":"malicious"})

    def test_presence_only_clears_on_valid_task_list_with_matching_identity(self):
        for identity, output, expected in (
            ("A", "ACTIVITY MANAGER ACTIVITIES\nHist #0: ActivityRecord{a u0 com.google.android.apps.youtube.xr/.Main t2}", True),
            ("A", "ACTIVITY MANAGER ACTIVITIES\nHist #0: ActivityRecord{a u0 com.android.systemui/.Main t2}", False),
            ("A", "device disconnected", None),
            ("A", "ACTIVITY MANAGER ACTIVITIES\n", None),
            ("B", "ACTIVITY MANAGER ACTIVITIES\nHist #0: ActivityRecord{a u0 com.android.systemui/.Main t2}", None),
        ):
            with self.subTest(identity=identity, output=output), \
                 patch.object(server, "resolve_target", return_value="USB"), \
                 patch.object(server.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, identity, ""), subprocess.CompletedProcess([], 0, output, "")]) as run:
                result = server.app_presence({"target":"USB", "physical_serial":"A", "app":"youtube"})
                self.assertIs(result["present"], expected)
                if identity != "A":
                    self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
