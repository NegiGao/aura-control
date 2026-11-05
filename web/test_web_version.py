import json
import threading
import unittest
from urllib.request import Request, urlopen
from unittest.mock import patch

import server


class WebVersionTest(unittest.TestCase):
    def test_freshness_checks_do_not_touch_adb_or_reuse_old_code(self):
        with server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler) as httpd:
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{httpd.server_port}"
            try:
                with patch.object(server.subprocess, "run", side_effect=AssertionError("unexpected subprocess")):
                    with urlopen(base + "/api/version", timeout=3) as response:
                        self.assertEqual(response.headers["Cache-Control"], "no-store")
                        self.assertEqual(json.load(response), {
                            "version": server.VERSION,
                            "page_version": server.VERSION,
                            "projection_running": False,
                        })
                    for path in ["/?launch=test", "/index.html", "/ui_languages.js?v=test",
                                 "/i18n.js?v=test", "/experience_timers.js?v=test"]:
                        with self.subTest(path=path):
                            request = Request(base + path, headers={"If-Modified-Since": "Mon, 01 Jan 2099 00:00:00 GMT"})
                            with urlopen(request, timeout=3) as response:
                                self.assertEqual(response.status, 200)
                                self.assertEqual(response.headers["Cache-Control"], "no-store")
                                if path.startswith(("/?", "/index.html")):
                                    self.assertIn(f" / V{server.VERSION}".encode(), response.read())
                    with urlopen(base + "/icons/youtube.png", timeout=3) as response:
                        self.assertIsNone(response.headers["Cache-Control"])
            finally:
                httpd.shutdown()
                thread.join(3)


if __name__ == "__main__":
    unittest.main()
