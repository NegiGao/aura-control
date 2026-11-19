import os
import subprocess
import unittest
from unittest.mock import patch

import browser_launcher as launcher


class BrowserLauncherTest(unittest.TestCase):
    def call(self, processes="", prefer=False, result=0):
        with (patch.dict(os.environ, {}, clear=True),
              patch.object(launcher.subprocess, "run") as run,
              patch.object(launcher.webbrowser, "open") as browser):
            run.side_effect = [
                subprocess.CompletedProcess([], 0, processes),
                subprocess.CompletedProcess([], result),
            ]
            launcher.open_control_page("http://127.0.0.1:8775/?v=3.12.9", prefer)
            return [call.args[0] for call in run.call_args_list], browser.call_args_list

    def test_headless_chrome_gets_visible_new_window(self):
        for flag in ["--headless", "--headless=new"]:
            calls, browsers = self.call(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome " + flag
            )
            self.assertEqual(calls[1], [
                "/usr/bin/open", "-n", "-a", "Google Chrome", "--args", "--new-window",
                "http://127.0.0.1:8775/?v=3.12.9",
            ])
            self.assertFalse(browsers)

    def test_normal_public_keeps_default_browser(self):
        calls, browsers = self.call()
        self.assertEqual(len(calls), 1)
        self.assertEqual(browsers[0].args, ("http://127.0.0.1:8775/?v=3.12.9",))

    def test_normal_internal_keeps_chrome(self):
        calls, browsers = self.call(prefer=True)
        self.assertEqual(calls[1][0:4], ["/usr/bin/open", "-a", "Google Chrome",
                                       "http://127.0.0.1:8775/?v=3.12.9"])
        self.assertFalse(browsers)

    def test_browser_override_is_preserved(self):
        with (patch.dict(os.environ, {"BROWSER": "/usr/bin/true"}),
              patch.object(launcher.subprocess, "run") as run,
              patch.object(launcher.webbrowser, "open") as browser):
            launcher.open_control_page("http://127.0.0.1:8775/")
            run.assert_not_called()
            browser.assert_called_once_with("http://127.0.0.1:8775/")

    def test_browser_failure_uses_default(self):
        self.assertTrue(self.call(prefer=True, result=1)[1])

    def test_probe_timeout_uses_default(self):
        with (patch.dict(os.environ, {}, clear=True),
              patch.object(launcher.subprocess, "run",
                           side_effect=subprocess.TimeoutExpired("ps", 2)),
              patch.object(launcher.webbrowser, "open") as browser):
            launcher.open_control_page("http://127.0.0.1:8775/")
            browser.assert_called_once()

    def test_other_app_with_headless_flag_is_ignored(self):
        calls, browsers = self.call("/usr/local/bin/node --headless")
        self.assertEqual(len(calls), 1)
        self.assertTrue(browsers)


if __name__ == "__main__":
    unittest.main()
