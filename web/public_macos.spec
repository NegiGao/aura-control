from pathlib import Path

root = Path(SPECPATH)
version = (root / "VERSION").read_text().strip()
a = Analysis(
    [str(root / "public_launcher.py")],
    pathex=[str(root)],
    binaries=[],
    datas=[(str(root / name), destination) for name, destination in (
        ("index.html", "."), ("VERSION", "."),
        ("ui_languages.js", "."), ("i18n.js", "."),
        ("experience_timers.js", "."), ("icons", "icons"),
        ("scrcpy-macos-aarch64-v4.0", "scrcpy-macos-aarch64-v4.0"),
    )],
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [], exclude_binaries=True,
    name="Aura Control Public", debug=False, bootloader_ignore_signals=False,
    strip=False, upx=False, console=False, argv_emulation=False, target_arch="arm64",
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="Aura Control Public")
app = BUNDLE(
    coll, name="Aura Control Public.app",
    bundle_identifier="com.negigao.aura-control.public.macos",
    info_plist={"CFBundleShortVersionString": version, "CFBundleVersion": version,
                "LSMinimumSystemVersion": "14.0", "NSHighResolutionCapable": True,
                "NSLocalNetworkUsageDescription": "Connect to the Android XR device you select for wireless mirroring."},
)
