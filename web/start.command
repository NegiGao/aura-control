#!/bin/bash
set -eu
cd "$(dirname "$0")"
if ! command -v adb >/dev/null || ! command -v scrcpy >/dev/null; then
  echo 'Install prerequisites: brew install scrcpy android-platform-tools'
  exit 1
fi
exec python3 server.py
