#!/bin/bash
set -eu
source_app="$1"
destination_app="$2"
entry_script="$(cd "$(dirname "$0")" && pwd)/macos_reopen.applescript"
[[ -d "$source_app/Contents" && ! -e "$destination_app" ]]
version=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$source_app/Contents/Info.plist")
bundle_id=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$source_app/Contents/Info.plist")
/usr/bin/osacompile -s -o "$destination_app" "$entry_script"
/usr/bin/ditto "$source_app" "$destination_app/Contents/Resources/Backend.app"
inner="$destination_app/Contents/Resources/Backend.app/Contents/Info.plist"
outer="$destination_app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set CFBundleIdentifier $bundle_id.backend" "$inner"
/usr/libexec/PlistBuddy -c 'Add LSUIElement bool true' "$inner"
/usr/libexec/PlistBuddy -c "Add CFBundleIdentifier string $bundle_id" "$outer"
/usr/libexec/PlistBuddy -c "Add CFBundleShortVersionString string $version" "$outer"
/usr/libexec/PlistBuddy -c "Add CFBundleVersion string $version" "$outer"
/usr/libexec/PlistBuddy -c 'Add LSMinimumSystemVersion string 14.0' "$outer"
/usr/libexec/PlistBuddy -c 'Add NSLocalNetworkUsageDescription string Connect to the Android XR device you select for wireless mirroring.' "$outer"
/usr/bin/codesign --force --deep --sign - "$destination_app/Contents/Resources/Backend.app"
/usr/bin/codesign --force --deep --sign - "$destination_app"
/usr/bin/codesign --verify --deep --strict "$destination_app"
