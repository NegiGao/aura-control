# Aura Control Public

[中文](#中文) · [日本語](#日本語)

An independent Android and local-web screen controller by [NegiGao](https://github.com/NegiGao), based on scrcpy. Intended for Android / Android XR devices, including compatible XREAL hardware. Not an official XREAL or Google product.

## Public edition

- **Current Android baseline: 0.49-public (versionCode 2).** This replaces the earlier 1.0.0-public preview: the app version label follows the original 0.49 baseline, while the higher internal version code permits an update of the earlier public APK. The current web version is 3.12.8.
- Android handset controller: USB/OTG and supported Wi-Fi ADB connections, video/audio forwarding, external-display output and on-device controls.
- Local web controller: three independent device slots, USB/Wi-Fi selection, quality/frame-rate options, MP4 recording and recording-folder access.
- Web 3.12.8 restarts a Wi-Fi projection that exits unexpectedly. Recovery matches the hardware serial when the device can be rediscovered, even if its IP changes. After an 18-second Wi-Fi grace period it tries the same device over USB if available; it does not automatically leave a successful USB fallback. Stop cancels recovery. An active recording resumes in a new MP4 file; footage during the outage is not recovered. This does not detect a frozen picture while scrcpy is still running.
- Original 0.49 controls and layout are retained: global 15-minute timer, independent app timers, Maps XR tutorial/landmarks, Gemini ON/OFF, volume, five-level transparency, launcher, arrange/recenter and five-app workset. YouTube XR, Maps 2D/XR and Gemini launch shortcuts remain; the Melody, HoloModels, Demeo, BRINK and Sock Puppet launch buttons are removed. Apps must already be installed on the controlled device. This repository does **not** distribute those apps.
- The web URL input, Open, Save and saved-site dropdown remain. The first-run saved-site list is empty. Bookmarks stay in your browser; no sample bookmarks or device records are shipped.
- XR controls use the original 0.49 device commands, some requiring `su 0` / compatible development firmware. Do not assume these controls work on stock firmware or arbitrary Android devices. Maps XR's tutorial control resets onboarding preferences after creating a backup; normal/landmark launch marks onboarding complete. These actions intentionally change the selected app's tutorial state.

## Isolation

The public Android ID is `dev.negigao.auracontrol.publicapp`. It can coexist with other controller editions without importing their app data. The web server uses `127.0.0.1:8775`, separate browser-storage keys and a separate `Aura Control Public` data directory. Original/private project history is not included.

## Android build

Requirements: JDK 17, Android SDK 35 and an Android 8+ controller phone supporting the required USB host/display features.

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

The development APK is `android/app/build/outputs/apk/debug/app-debug.apk`. This is a debug build, not a production-store release. Use your own private release signing key for a distributed release; never commit signing keys or ADB identity keys. A different signing key cannot update an existing installation without removing it.

The bundled Android scrcpy server is the unmodified official v4.1 artifact. See `THIRD_PARTY_NOTICES.md`. TLS wireless-debugging pairing is device/build dependent; USB setup is the main bootstrap path. External-display support depends on the phone's hardware and Android firmware.

## Local web use on macOS

Install Python 3, scrcpy and Android platform tools. With Homebrew:

```sh
brew install scrcpy android-platform-tools
cd web
python3 server.py
```

Open **http://127.0.0.1:8775/** in a browser, not `index.html` as a local file. The server must stay running. `web/start.command` checks prerequisites and starts it.

Optional environment variables: `AURA_ADB`, `AURA_SCRCPY`, `AURA_SCRCPY_SERVER`, `AURA_CONTROL_PORT`, `AURA_PUBLIC_DATA_DIR`. Recordings and logs default to `~/Library/Application Support/Aura Control Public/`. No old recordings or logs are included.

## Connect safely

1. Enable developer options and USB debugging on the **controlled** device. Accept its debugging authorization prompt.
2. Use a data-capable cable and a USB host-capable port on the controller. A charging-only port/cable will not work.
3. For Wi-Fi, use a trusted shared network and the device's actual ADB address/port. USB can bootstrap supported TCP/IP ADB. Android's TLS wireless debugging uses different pairing/connection ports; do not assume they are always 5555.
4. Select the intended device in each web slot before controlling apps. Computer audio is limited to one active source at a time.
5. Disconnect/revoke debugging when finished. Do not expose the web port or ADB port to the Internet. Only use devices and app accounts you are authorized to control.

## Tests

```sh
python3 -m unittest discover -s web -p 'test_*.py'
cd android && ./gradlew testDebugUnitTest
```

The browser regression test uses mocked devices and requests only; it never contacts a real headset. Install Playwright in your development environment and run `node web/test_public_browser.cjs`. The optional `AURA_PLAYWRIGHT` environment variable selects an installed Playwright module.

Automated tests and a successful build are not proof of real headset rendering, app availability, account access, audio routing or external-display compatibility. Test these on your actual hardware.

## Attribution and licenses

Android client foundation: [feggaa/scrcpy-android](https://github.com/feggaa/scrcpy-android), by Laid Feggaa (Rabi3), under the MIT license retained in `android/LICENSE`. scrcpy server: [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy), Apache-2.0. License texts and artifact provenance are retained. Google and XREAL names and the Maps/YouTube shortcut icons belong to their respective owners and identify the launch target only, not endorsement. No Google app binaries are bundled.

Public source availability does not remove third-party license obligations. The standalone web code has no additional root-level license grant selected yet.

## 中文

这是 NegiGao 的个人独立公开版，不是 XREAL / Google 官方产品。手机保持 0.49，网页更新到 3.12.8。保留原有布局、15 分钟与 APP 独立计时、Maps XR 教程与地标、Gemini 开关、五档透光度、音量、排列／中心重置及五 APP 同开；仅移除 Melody、HoloModels、Demeo、BRINK、Sock Puppet 的启动按钮。原公司自用版不改动，不包含旧收藏、设备记录或日志。

网页投屏异常退出后，若能重新发现设备，即使 IP 变了也会按硬件序号尝试恢复同一台设备的 Wi-Fi 投屏；约 18 秒仍未恢复时优先尝试同一台设备的 USB。切到 USB 后不会自动跳回 Wi-Fi，点击「停止」会取消恢复。录像恢复时会另存一段 MP4，断线期间的画面无法补回。投屏进程未退出的画面冻结不在此功能范围内。

公开 APK 显示版本 `0.49-public`，内部版本号提升为 2，可更新此前的 `1.0.0-public`（需同一签名）。XR 操作使用 0.49 原有命令，部分需要兼容的开发固件和 `su 0` 权限；普通量产系统不保证支持。Maps XR 教程会备份并重置引导状态，地标与普通启动会标记引导完成。

手机使用独立应用 ID；网页使用独立端口 `8775`、数据目录和收藏键，不会读取其他版本的数据。网页的“输入网址／打开／保存／下拉选择”保留，首次启动列表为空。Google APP 必须已安装在被控设备上。

被控设备需要开启开发者模式、USB 调试并允许连接。无线连接需可信局域网；不要把 ADB 或控制网页暴露到公网。手机 USB 主机能力、外接显示与无线配对支持因机型而异。启动网页时需保持 Python 后台运行，不能只双击 HTML 文件。

## 日本語

NegiGao による個人の独立公開版です。XREAL / Google の公式製品ではありません。Android は 0.49 のまま、Web は 3.12.8 に更新しました。レイアウト、15分・アプリ別タイマー、Maps XR のガイドと地名、Gemini ON/OFF、調光・音量・整列・中心リセット・5アプリ同時起動を維持し、Melody・HoloModels・Demeo・BRINK・Sock Puppet の起動ボタンのみを除きます。社内版、既存の保存サイト、端末記録は変更・取り込みしません。

Web では Wi-Fi 投影が異常終了した場合、端末を再検出できれば、IP アドレスが変わってもシリアル番号で同一端末を確認して復旧を試みます。約18秒で復旧できない場合は同じ端末の USB 接続を優先します。USB に切り替えた後は自動で Wi-Fi に戻らず、「停止」で復旧を中止できます。録画の再開時は別の MP4 に保存され、切断中の映像は補完されません。投影プロセスが残ったまま映像が止まる場合は対象外です。

表示バージョンは `0.49-public`、内部バージョンコードは2です。XR操作の一部は対応開発用ファームウェアと `su 0` 権限が必要です。Maps XR のガイドは設定をバックアップして初回状態に戻し、通常起動・地名検索はガイド完了状態に変更します。

AndroidアプリID、Webのポート `8775`、保存領域は他の版と分離しています。URL入力・開く・保存・保存先一覧は維持し、初回の一覧は空です。対象アプリは操作先の端末に別途インストールしてください。

対象端末で開発者向けオプションとUSBデバッグを有効にし、接続を許可してください。信頼できるLANを使用し、ADBや操作サーバーをインターネットに公開しないでください。実機映像・音声・外部出力の互換性は各端末で確認が必要です。
