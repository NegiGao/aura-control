"""Offline identity checks for recovery after a Wi-Fi projection exits."""
import subprocess
import unittest
from unittest.mock import patch

import server


class RecoveryIdentityTest(unittest.TestCase):
    USB = "PUCK-USB"
    PHYSICAL = "PUCK-1"
    OLD = "192.0.2.10:5555"
    NEW = "192.0.2.11:5555"

    def setUp(self):
        self.devices = []
        self.serials = {}
        self.wlan_ip = ""
        self.mdns = ""
        self.commands = []
        self.cache = {self.OLD: {"serial": self.PHYSICAL, "address": self.OLD}}
        self.streams = {}
        for item in (
            patch.object(server, "verified_wireless", self.cache),
            patch.object(server, "streams", self.streams),
            patch.object(server.subprocess, "run", side_effect=self.run_adb),
        ):
            item.start()
            self.addCleanup(item.stop)

    def run_adb(self, command, **kwargs):
        args = command[1:]
        self.commands.append(args)
        self.assertLessEqual(kwargs["timeout"], 2)
        if args == ["devices", "-l"]:
            output = "List of devices attached\n" + "\n".join(self.devices)
        elif args == ["mdns", "services"]:
            output = self.mdns
        elif args[0] == "connect":
            output = ""
        elif args[2:] == ["shell", "getprop", "ro.serialno"]:
            output = self.serials.get(args[1], "")
        elif args[2:] == ["shell", "ip", "-4", "addr", "show", "wlan0"]:
            output = f"inet {self.wlan_ip}/24" if self.wlan_ip else ""
        else:
            raise AssertionError(f"unexpected ADB command: {args}")
        return subprocess.CompletedProcess(command, 0, output, "")

    def test_reads_new_ip_from_only_matching_usb_and_replaces_old_address(self):
        self.devices = [
            "OTHER-USB device usb:1-1",
            f"{self.USB} device usb:1-2",
            f"{self.OLD} offline",
        ]
        self.serials = {"OTHER-USB": "OTHER", self.USB: self.PHYSICAL, self.NEW: self.PHYSICAL}
        self.wlan_ip = "192.0.2.11"
        self.assertEqual(server.recovery_target(self.PHYSICAL, False), self.NEW)
        self.assertIn(["connect", self.NEW], self.commands)
        self.assertNotIn(["connect", self.OLD], self.commands)
        self.assertNotIn(self.OLD, self.cache)

    def test_uses_authorized_changed_address_without_usb(self):
        self.devices = [f"{self.NEW} device"]
        self.serials = {self.NEW: self.PHYSICAL}
        self.assertEqual(server.recovery_target(self.PHYSICAL, False), self.NEW)
        self.assertFalse(any(args[0] == "connect" for args in self.commands))

    def test_uses_exact_legacy_mdns_without_usb_and_checks_identity(self):
        self.mdns = (
            f"adb-{self.PHYSICAL}-OTHER _adb._tcp 192.0.2.40:5555\n"
            f"adb-{self.PHYSICAL} _adb-tls-connect._tcp 192.0.2.50:5555\n"
            f"adb-{self.PHYSICAL} _adb._tcp {self.NEW}\n"
        )
        self.serials = {self.NEW: self.PHYSICAL}
        self.assertEqual(server.recovery_target(self.PHYSICAL, False), self.NEW)
        self.assertEqual([args for args in self.commands if args[0] == "connect"], [["connect", self.NEW]])

    def test_ignores_reused_wifi_address_and_other_active_device(self):
        other_target = "192.0.2.20:5555"
        self.devices = [f"{self.OLD} device", f"{other_target} device"]
        self.serials = {self.OLD: "DIFFERENT", other_target: "OTHER"}
        self.streams["2"] = {"physical_serial": "OTHER", "serial": other_target}
        self.assertIsNone(server.recovery_target(self.PHYSICAL, False))
        self.assertTrue(all(other_target not in args for args in self.commands))

    def test_usb_fallback_rejects_a_different_physical_device(self):
        self.devices = [f"{self.USB} device usb:1-2"]
        self.serials = {self.USB: "DIFFERENT"}
        self.assertIsNone(server.recovery_target(self.PHYSICAL, True))
        self.serials[self.USB] = self.PHYSICAL
        self.assertEqual(server.recovery_target(self.PHYSICAL, True), self.USB)

    def test_usb_fallback_works_while_wifi_monitor_holds_its_lock(self):
        self.devices = [f"{self.USB} device usb:1-2"]
        self.serials = {self.USB: self.PHYSICAL}
        with server.wireless_lock:
            self.assertIsNone(server.recovery_target(self.PHYSICAL, False))
            self.assertEqual(server.recovery_target(self.PHYSICAL, True), self.USB)


if __name__ == "__main__":
    unittest.main()
