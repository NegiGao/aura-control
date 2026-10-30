"""Offline checks: wireless bootstrap must not interrupt wired projection."""
import subprocess
import unittest
from unittest.mock import Mock, patch

import server


class UsbWirelessFallbackTest(unittest.TestCase):
    def setUp(self):
        self.patches = [
            patch.object(server, "wireless_bootstrap_attempts", set()),
            patch.object(server, "verified_wireless", {}),
            patch.object(server, "preferred_wireless", None),
            patch.object(server, "wireless_error", ""),
            patch.object(server, "streams", {}),
            patch.object(server, "connection_state", return_value=("USB-TEST", [])),
            patch.object(server, "adb_devices", return_value=[{
                "serial": "USB-TEST", "usb": True, "model": "Android_XR_Puck",
            }]),
            patch.object(server, "device_wifi_ip", return_value="192.0.2.1"),
            patch.object(server, "wireless_is_ready", return_value=False),
            patch.object(server.time, "sleep"),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def run_adb(self, tcp_port):
        def result(command, **kwargs):
            output = "PHYSICAL-TEST" if command[-1] == "ro.serialno" else ""
            if command[-1] == "service.adb.tcp.port":
                output = tcp_port
            return subprocess.CompletedProcess(command, 0, output, "")
        return patch.object(server.subprocess, "run", side_effect=result)

    def test_unreachable_network_does_not_restart_already_enabled_tcp_adb(self):
        with self.run_adb("5555") as run:
            for _ in range(3):
                self.assertEqual(server.ensure_wireless(), ("USB-TEST", None))
            self.assertFalse(any("tcpip" in call.args[0] for call in run.call_args_list))

    def test_failed_bootstrap_is_attempted_only_once_per_device_and_address(self):
        with self.run_adb("") as run:
            for _ in range(3):
                server.ensure_wireless()
            self.assertEqual(sum("tcpip" in call.args[0] for call in run.call_args_list), 1)

    def test_active_usb_projection_is_never_restarted_for_wireless_setup(self):
        process = Mock()
        process.poll.return_value = None
        server.streams["1"] = {"process": process, "serial": "USB-TEST", "connection": "usb"}
        with self.run_adb("") as run:
            server.ensure_wireless()
            self.assertFalse(any("tcpip" in call.args[0] for call in run.call_args_list))
            self.assertEqual(server.wireless_bootstrap_attempts, set())

    def test_wireless_can_recover_without_restarting_usb(self):
        with self.run_adb("5555") as run:
            server.ensure_wireless()
            with patch.object(server, "connection_state", return_value=("USB-TEST", ["192.0.2.1:5555"])), \
                 patch.object(server, "wireless_is_ready", return_value=True):
                self.assertEqual(server.ensure_wireless(), ("USB-TEST", "192.0.2.1:5555"))
            self.assertEqual(server.verified_wireless["192.0.2.1:5555"]["serial"], "PHYSICAL-TEST")
            self.assertFalse(any("tcpip" in call.args[0] for call in run.call_args_list))

    def test_auto_prefers_usb_and_does_not_trigger_wireless_bootstrap(self):
        with patch.object(server, "ensure_wireless", side_effect=AssertionError("must not restart ADB")), \
             patch.object(server, "connection_state", return_value=("USB-TEST", ["192.0.2.1:5555"])):
            self.assertEqual(server.resolve_target("auto"), "USB-TEST")

    def test_auto_uses_existing_wireless_when_no_usb_is_connected(self):
        with patch.object(server, "connection_state", return_value=(None, ["192.0.2.1:5555"])):
            self.assertEqual(server.resolve_target("auto"), "192.0.2.1:5555")
        with patch.object(server, "connection_state", return_value=(None, [])):
            self.assertIsNone(server.resolve_target("auto"))

    def test_wireless_recovers_after_usb_is_unplugged_without_restarting_daemon(self):
        target = "192.0.2.1:5555"
        server.verified_wireless[target] = {"serial": "PHYSICAL-TEST", "address": target}
        server.preferred_wireless = target
        with self.run_adb("5555") as run, \
             patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", side_effect=[False, True]):
            self.assertEqual(server.ensure_wireless(), (None, target))
            commands = [call.args[0] for call in run.call_args_list]
            self.assertIn([str(server.ADB), "disconnect", target], commands)
            self.assertIn([str(server.ADB), "connect", target], commands)
            self.assertFalse(any("tcpip" in command or "kill-server" in command for command in commands))

    def test_wireless_recovery_rejects_address_reassigned_to_another_device(self):
        target = "192.0.2.1:5555"
        server.verified_wireless[target] = {"serial": "OTHER-PHYSICAL", "address": target}
        with self.run_adb("5555"), \
             patch.object(server, "connection_state", return_value=(None, [target])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", return_value=True):
            self.assertEqual(server.ensure_wireless(), (None, None))
            self.assertIsNone(server.preferred_wireless)

    def test_wireless_recovery_does_not_guess_unknown_addresses(self):
        with self.run_adb("5555") as run, \
             patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]):
            self.assertEqual(server.ensure_wireless(), (None, None))
            run.assert_not_called()

    def test_controller_restart_remembers_identity_of_existing_authorized_session(self):
        target = "192.0.2.1:5555"
        with self.run_adb("5555") as run, \
             patch.object(server, "connection_state", return_value=(None, [target])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", return_value=True):
            self.assertEqual(server.ensure_wireless(), (None, target))
            self.assertEqual(server.verified_wireless[target]["serial"], "PHYSICAL-TEST")
            self.assertFalse(any("connect" in call.args[0] for call in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
