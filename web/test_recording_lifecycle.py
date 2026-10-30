"""Recorder stop/exit checks without touching devices or converting files."""
import unittest
from unittest.mock import Mock, patch

import server


class RecordingLifecycleTest(unittest.TestCase):
    def test_stop_waits_for_recorder_once_without_conversion(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(server, "streams", {"1": {"process": process}}), \
             patch.object(server.os, "killpg") as killed, \
             patch.object(server.threading, "Thread") as worker:
            self.assertTrue(server.stop_scrcpy("1"))
            self.assertFalse(server.stop_scrcpy("1"))
            process.wait.assert_called_once_with(timeout=3)
            killed.assert_called_once_with(process.pid, server.signal.SIGTERM)
            worker.assert_not_called()

    def test_natural_exit_clears_stream_without_conversion(self):
        process = Mock()
        process.poll.return_value = 0
        with patch.object(server, "streams", {"1": {"process": process}}), \
             patch.object(server.threading, "Thread") as worker:
            server.cleanup_streams()
            server.cleanup_streams()
            self.assertEqual(server.streams, {})
            worker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
