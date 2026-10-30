"""Public-edition, device-free regression checks."""
import subprocess
import unittest
from unittest.mock import patch
import server


class PublicEditionTests(unittest.TestCase):
    def test_baseline_google_xr_catalog_and_separate_port(self):
        self.assertEqual({"youtube", "google-maps", "maps-xr", "maps-new", "chrome"}, set(server.APPS))
        self.assertTrue(server.APPS["youtube"].startswith("com.google.android.apps.youtube.xr/"))
        self.assertTrue(server.APPS["maps-xr"].startswith("com.google.android.apps.immersiveviewxr/"))
        self.assertEqual(8775, server.PORT)

    def test_launcher_targets_only_selected_device(self):
        for app in ("youtube", "google-maps", "chrome"):
            package = server.APPS[app]
            with self.subTest(app=app), patch.object(server, "resolve_target", return_value="TEST-DEVICE"), \
                 patch.object(server.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "Status: ok", "")) as run, \
                 patch.object(server, "confirm_app_task") as confirm:
                self.assertEqual("TEST-DEVICE", server.launch_app({"target": "TEST-DEVICE", "app": app}))
                args = run.call_args.args[0]
                self.assertEqual(["-s", "TEST-DEVICE"], args[1:3])
                self.assertEqual(["-n", package], args[-2:])
                confirm.assert_called_once_with("TEST-DEVICE", package.split("/", 1)[0])

    def test_unknown_app_and_out_of_range_dimming_are_rejected(self):
        with self.assertRaises(ValueError):
            server.launch_app({"app": "not-in-public-catalog"})
        with self.assertRaises(ValueError):
            server.control_device({"action": "unknown"})
        with patch.object(server, "resolve_target", return_value="TEST-DEVICE"), self.assertRaises(ValueError):
            server.tune_device({"action": "dimming", "value": 9})


if __name__ == "__main__":
    unittest.main()
