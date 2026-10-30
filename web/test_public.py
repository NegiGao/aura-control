"""Public-edition, device-free regression checks."""
import subprocess
import unittest
from unittest.mock import patch
import server


class PublicEditionTests(unittest.TestCase):
    def test_google_2d_catalog_and_separate_port(self):
        self.assertEqual(11, len(server.APPS))
        self.assertEqual("com.google.android.youtube", server.APPS["youtube"])
        self.assertEqual("com.google.android.apps.photos", server.APPS["photos"])
        self.assertEqual(8775, server.PORT)
        self.assertFalse(any(".xr" in package for package in server.APPS.values()))

    def test_launcher_targets_only_selected_device(self):
        for app, package in server.APPS.items():
            with self.subTest(app=app), patch.object(server, "resolve_target", return_value="TEST-DEVICE"), \
                 patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Status: ok", "")) as run, \
                 patch.object(server, "confirm_app_task") as confirm:
                self.assertEqual("TEST-DEVICE", server.launch_app({"target": "TEST-DEVICE", "app": app}))
                args = run.call_args.args[0]
                self.assertEqual(["-s", "TEST-DEVICE"], args[1:3])
                self.assertEqual(["-p", package], args[-2:])
                confirm.assert_called_once_with("TEST-DEVICE", package)

    def test_unknown_app_and_private_commands_are_rejected(self):
        with self.assertRaises(ValueError):
            server.launch_app({"app": "not-in-public-catalog"})
        with self.assertRaises(ValueError):
            server.control_device({"action": "open-workset"})
        with self.assertRaises(ValueError):
            server.tune_device({"action": "dimming"})


if __name__ == "__main__":
    unittest.main()
