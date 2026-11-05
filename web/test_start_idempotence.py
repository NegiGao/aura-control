"""Repeated Start must not restart a healthy stream or touch another slot."""
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

import server


class StartIdempotenceTest(unittest.TestCase):
    def run_case(self, change=None, record=False):
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("scrcpy", 1)
        other = Mock()
        other.poll.return_value = None
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            streams = {"2": {"process": other, "physical_serial": "OTHER", "audio": "no-audio"}}
            for name, value in (("streams", streams), ("DATA_ROOT", Path(directory)),
                                ("RECORDINGS", Path(directory) / "recordings")):
                stack.enter_context(patch.object(server, name, value))
            for name, value in (("resolve_target", "192.0.2.1:5555"), ("internal_display_id", "1"),
                                ("quarter_screen_size", (960, 600)), ("scrcpy_environment", {})):
                stack.enter_context(patch.object(server, name, return_value=value))
            stack.enter_context(patch.object(server.subprocess, "run", return_value=
                                subprocess.CompletedProcess([], 0, "TEST-PHYSICAL", "")))
            spawned = stack.enter_context(patch.object(server.subprocess, "Popen", return_value=process))
            killed = stack.enter_context(patch.object(server.os, "killpg"))
            options = {"slot": "1", "target": "test", "audio": "both", "record": record}
            server.start_scrcpy(options)
            process.wait.side_effect = [0, subprocess.TimeoutExpired("scrcpy", 1)]
            result = server.start_scrcpy({**options, **(change or {})})
            self.assertIs(server.streams["2"]["process"], other)
            if change:
                self.assertEqual(spawned.call_count, 2)
                killed.assert_called_once_with(process.pid, server.signal.SIGTERM)
                self.assertIsNotNone(result[2])
            else:
                spawned.assert_called_once()
                killed.assert_not_called()
                self.assertIsNone(result[2])
                self.assertEqual(result[3], "both")

    def test_repeated_start_is_a_noop(self):
        self.run_case()

    def test_repeated_record_start_keeps_the_same_file(self):
        self.run_case(record=True)

    def test_changed_quality_restarts_only_requested_slot(self):
        self.run_case({"frame_rate": "60"})


if __name__ == "__main__":
    unittest.main()
