"""Open the control page without handing it to windowless Chrome."""
import os
import re
import subprocess
import webbrowser


def open_control_page(url, prefer_chrome=False):
    if not os.environ.get("BROWSER"):
        try:
            processes = subprocess.run(
                ["/bin/ps", "-axo", "command="], capture_output=True,
                text=True, timeout=2, check=False,
            )
            headless = any(
                line.startswith("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome ")
                and re.search(r"\s--headless(?:[=\s]|$)", line)
                for line in processes.stdout.splitlines()
            )
            if headless or prefer_chrome:
                command = ["/usr/bin/open"]
                if headless:
                    command += ["-n"]
                command += ["-a", "Google Chrome"]
                command += ["--args", "--new-window", url] if headless else [url]
                result = subprocess.run(
                    command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=5, check=False,
                )
                if result.returncode == 0:
                    return
        except (OSError, subprocess.TimeoutExpired):
            pass
    webbrowser.open(url)
