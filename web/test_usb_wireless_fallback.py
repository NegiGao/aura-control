"""Offline checks: wireless bootstrap must not interrupt wired projection."""
import subprocess
import unittest
from unittest.mock import Mock, patch

import server


class UsbWirelessFallbackTest(unittest.TestCase):
    def setUp(self):
        self.patches = [
            patch.object(server, "wireless_bootstrap_attempts", set()),
            patch.object(server, "wireless_retry_after", {}),
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
             patch.object(server, "wireless_is_ready", return_value=True):
            self.assertEqual(server.ensure_wireless(), (None, target))
            commands = [call.args[0] for call in run.call_args_list]
            self.assertNotIn([str(server.ADB), "disconnect", target], commands)
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

    def test_running_wireless_video_is_not_probed_or_disconnected(self):
        target = "192.0.2.1:5555"
        process = Mock()
        process.poll.return_value = None
        server.verified_wireless[target] = {"serial": "PHYSICAL-TEST", "address": target}
        server.streams["1"] = {"process": process, "serial": target,
                               "physical_serial": "PHYSICAL-TEST", "connection": "wireless"}
        with patch.object(server, "connection_state", return_value=(None, [target])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", side_effect=AssertionError("do not probe active video")), \
             patch.object(server.subprocess, "run", side_effect=AssertionError("do not reset active ADB")):
            self.assertEqual(server.ensure_wireless(discover=False), (None, target))

    def test_recovery_does_not_disconnect_on_a_single_failed_probe(self):
        target = "192.0.2.1:5555"
        server.verified_wireless[target] = {"serial": "PHYSICAL-TEST", "address": target}
        with self.run_adb("5555") as run, \
             patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]):
            server.ensure_wireless(discover=False)
            self.assertFalse(any("disconnect" in call.args[0] for call in run.call_args_list))

    def test_unreachable_first_headset_does_not_block_second_headset(self):
        first, second = "192.0.2.1:5555", "192.0.2.2:5555"
        server.verified_wireless.update({target: {"serial": "PHYSICAL-TEST", "address": target}
                                         for target in (first, second)})
        def probe(target):
            if target == first:
                raise subprocess.TimeoutExpired("adb", 5)
            return True
        with self.run_adb("5555"), \
             patch.object(server, "connection_state", return_value=(None, [second])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", side_effect=probe):
            self.assertEqual(server.ensure_wireless(discover=False), (None, second))

    def test_verified_new_address_replaces_old_address_for_the_same_headset(self):
        old, new = "192.0.2.1:5555", "192.0.2.2:5555"
        server.verified_wireless[old] = {"serial": "PHYSICAL-TEST", "address": old}
        with self.run_adb("5555"), patch.object(server, "wireless_is_ready", return_value=True):
            self.assertTrue(server.confirm_wireless(new, "PHYSICAL-TEST"))
        self.assertNotIn(old, server.verified_wireless)
        self.assertEqual(server.verified_wireless[new]["serial"], "PHYSICAL-TEST")

    def test_active_preferred_headset_does_not_starve_another_authorized_device(self):
        first, second = "192.0.2.1:5555", "192.0.2.2:5555"
        process = Mock()
        process.poll.return_value = None
        server.preferred_wireless = first
        server.verified_wireless[first] = {"serial": "FIRST", "address": first}
        server.streams["1"] = {"process": process, "serial": first,
                               "physical_serial": "FIRST", "connection": "wireless"}
        def run(command, **kwargs):
            self.assertNotIn(first, command)
            return subprocess.CompletedProcess(command, 0, "SECOND", "")
        with patch.object(server, "connection_state", return_value=(None, [first, second])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", side_effect=lambda target: target == second), \
             patch.object(server.subprocess, "run", side_effect=run):
            self.assertEqual(server.ensure_wireless(discover=False), (None, first))
        self.assertEqual(server.verified_wireless[second]["serial"], "SECOND")

    def test_running_unregistered_second_headset_is_remembered_without_probing(self):
        first, second = "192.0.2.1:5555", "192.0.2.2:5555"
        process = Mock()
        process.poll.return_value = None
        server.verified_wireless[first] = {"serial": "FIRST", "address": first}
        server.streams.update({str(index): {"process": process, "serial": target,
                                           "physical_serial": identity, "connection": "wireless"}
                               for index, target, identity in ((1, first, "FIRST"), (2, second, "SECOND"))})
        with patch.object(server, "connection_state", return_value=(None, [first, second])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server.subprocess, "run", side_effect=AssertionError("no probe during video")):
            self.assertEqual(server.ensure_wireless(discover=False), (None, first))
        self.assertEqual(server.verified_wireless[second]["serial"], "SECOND")

    def test_mdns_recovers_only_exact_known_legacy_service_and_verifies_serial(self):
        old, new = "192.0.2.1:5555", "192.0.2.2:5555"
        server.verified_wireless[old] = {"serial": "PHYSICAL-TEST", "address": old}
        services = ("adb-PHYSICAL-TEST-extra _adb._tcp 192.0.2.3:5555\n"
                    "adb-PHYSICAL-TEST _adb-tls-connect._tcp 192.0.2.4:37000\n"
                    f"adb-PHYSICAL-TEST _adb._tcp {new}\n")
        def run(command, **kwargs):
            output = services if command[-2:] == ["mdns", "services"] else "PHYSICAL-TEST"
            return subprocess.CompletedProcess(command, 0, output, "")
        with patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", return_value=True), \
             patch.object(server.subprocess, "run", side_effect=run) as calls:
            self.assertEqual(server.ensure_wireless(discover=False), (None, new))
        commands = [call.args[0] for call in calls.call_args_list]
        self.assertEqual([command for command in commands if "connect" in command], [[str(server.ADB), "connect", new]])
        self.assertIn([str(server.ADB), "-s", new, "shell", "getprop", "ro.serialno"], commands)
        self.assertNotIn(old, server.verified_wireless)

    def test_mdns_matching_name_does_not_bypass_actual_device_identity(self):
        old, new = "192.0.2.1:5555", "192.0.2.2:5555"
        server.verified_wireless[old] = {"serial": "EXPECTED", "address": old}
        def run(command, **kwargs):
            output = f"adb-EXPECTED _adb._tcp {new}" if command[-2:] == ["mdns", "services"] else "OTHER-DEVICE"
            return subprocess.CompletedProcess(command, 0, output, "")
        with patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server, "wireless_is_ready", return_value=True), \
             patch.object(server.subprocess, "run", side_effect=run):
            self.assertEqual(server.ensure_wireless(discover=False), (None, None))
        self.assertNotIn(new, server.verified_wireless)
        self.assertEqual(server.verified_wireless[old]["serial"], "EXPECTED")

    def test_missing_headsets_retry_fairly_with_fifteen_second_cooldown(self):
        first, second = "192.0.2.1:5555", "192.0.2.2:5555"
        server.verified_wireless.update({target: {"serial": identity, "address": target}
                                         for target, identity in ((first, "FIRST"), (second, "SECOND"))})
        with self.run_adb("5555") as calls, \
             patch.object(server, "connection_state", return_value=(None, [])), \
             patch.object(server, "adb_devices", return_value=[]), \
             patch.object(server.time, "monotonic", return_value=100):
            for _ in range(4):
                self.assertEqual(server.ensure_wireless(discover=False), (None, None))
            commands = [call.args[0] for call in calls.call_args_list if "connect" in call.args[0]]
            self.assertEqual(commands, [[str(server.ADB), "connect", first], [str(server.ADB), "connect", second]])
            with patch.object(server.time, "monotonic", return_value=115):
                server.ensure_wireless(discover=False)
            self.assertEqual(sum("connect" in call.args[0] for call in calls.call_args_list), 3)

    def test_unsupported_or_timed_out_mdns_falls_back_without_blocking_recovery(self):
        target = "192.0.2.1:5555"
        for failure in (subprocess.CompletedProcess([], 1, "", "unsupported"), subprocess.TimeoutExpired("mdns", 3)):
            server.verified_wireless.clear()
            server.wireless_retry_after.clear()
            server.preferred_wireless = None
            server.verified_wireless[target] = {"serial": "PHYSICAL-TEST", "address": target}
            def run(command, **kwargs):
                if command[-2:] == ["mdns", "services"]:
                    if isinstance(failure, Exception):
                        raise failure
                    return failure
                return subprocess.CompletedProcess(command, 0, "PHYSICAL-TEST", "")
            with patch.object(server, "connection_state", return_value=(None, [])), \
                 patch.object(server, "adb_devices", return_value=[]), \
                 patch.object(server, "wireless_is_ready", return_value=True), \
                 patch.object(server.subprocess, "run", side_effect=run):
                self.assertEqual(server.ensure_wireless(discover=False), (None, target))


if __name__ == "__main__":
    unittest.main()
