# 📺🐧 TVUbuntu — Run a Real Ubuntu on Your Android TV Box

<p align="center">
  <a href="README_zh-CN.md"><img src="https://img.shields.io/badge/%F0%9F%93%98%20%E4%B8%AD%E6%96%87%E6%96%87%E6%A1%A3-%E7%82%B9%E5%87%BB%E6%9F%A5%E7%9C%8B-E91E63" alt="中文文档"/></a>
  &nbsp;&nbsp;
  <a href="README.md"><img src="https://img.shields.io/badge/%F0%9F%93%97%20English-Read%20here-1A1A2E" alt="English"/></a>
</p>

> Turn any Android TV box or Android device into a tiny, always-on **Ubuntu server**.
> A Kotlin Android **TV** app boots a genuine Ubuntu (22.04 / 24.04 / 26.04) via a native **chroot**, auto-installs **OpenSSH**, and shows the SSH connection info right on your living-room screen.

[![Platform](https://img.shields.io/badge/platform-Android%20TV-1A1A2E?logo=android&logoColor=white)](https://www.android.com/tv/)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/min%20SDK-21%20(Android%205.0)-34A853)](https://developer.android.com/about/versions)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![APK](https://img.shields.io/badge/APK-TVUbuntu%201.5.1-orange)](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases)

---

## 🌟 Why TVUbuntu?

Most TV boxes are **wasted silicon** — sitting idle, running ad-riddled launchers. TVUbuntu gives that hardware a second life as a **real Linux server**:

- 🐧 **A genuine Ubuntu userland**, not a container-emulated shell. Run `apt`, compile code, host services.
- 💻 **SSH in from your laptop** — it behaves like any other headless Ubuntu box on your LAN.
- 🔌 **Always-on, low-power** — a TV box draws ~2–3 W and can stay up 24/7 as a homelab node.
- 💰 **Recycle what you own** — no need to buy a Raspberry Pi or a VPS.

```
┌─────────────────────────┐         SSH (port 22)        ┌──────────────────────────┐
│   Your Laptop / PC      │ ───────────────────────────▶ │   Android TV Box (root)  │
│   $ ssh root@192.168.x.x│                              │  ┌──────────────────────┐ │
│                         │                              │  │ Ubuntu chroot        │ │
│                         │                              │  │  ├─ openssh-server   │ │
│                         │                              │  │  ├─ nginx / PHP / DB │ │
│                         │                              │  │  └─ your services    │ │
│                         │                              │  └──────────────────────┘ │
└─────────────────────────┘                              └──────────────────────────┘
        ▲                                                          │
        │            TVUbuntu App shows host / user / password     │
        └──────────────────────────────────────────────────────────┘
```

---

## ✨ Features

| Feature | Description |
| --- | --- |
| 🐧 **Native chroot (Root mode)** | In Root mode, uses the kernel-native `chroot` — no `proot` binary, lighter and faster. |
| 📡 **Auto rootfs download** | App downloads the minimal `ubuntu-base` tarball and decodes gzip on-device (avoids the box's `toybox tar` quirks). |
| 🔑 **Zero-config SSH** | Installs & launches `openssh-server`, then displays host / user / password / port on the TV. |
| 🎮 **TV-remote UI** | D-Pad navigation, focus-scaling "cursor" animation, large high-contrast buttons — built for a remote, not a touchscreen. |
| 🌐 **Multi-architecture** | `arm64` (real boxes), `amd64` (x86_64 emulators like MuMu), `armhf` (32-bit ARM). |
| 🔄 **Multi-version** | Ubuntu **22.04** (jammy) / **24.04** (noble) / **26.04** (resolute), isolated per version+arch. |
| ⚙️ **Boot auto-start** | Optional "start Ubuntu on boot" + launch-on-boot receiver. |
| 🛡️ **Robust shell layer** | `ShellExecutor` auto-detects `su` modes (interactive vs `su -c`), streams live progress, survives large output. |
| 🚀 **Production web stack** | One-shot `bootstrap_server.sh` spins up **nginx + PHP-FPM + MariaDB + Redis** under `supervisor`. |
| 🖥️ **Command console** | A built-in terminal card on the main screen streams live shell output (`cat /etc/os-release`, `df -h`, etc.) without leaving the TV. |
| 🌱 **No-root Proot mode** | No root? Run Ubuntu via `proot` instead — the same real Ubuntu userland, just without privileged ports (<1024) and systemd. Perfect for locked-down or unrooted boxes. |
| 📡 **Automatic ADB acquisition** | On app launch and on boot, silently obtains ADB access (wireless TLS pairing on Android 11+, classic TCP 5555 with an accessibility auto-click fallback) — the Proot-over-ADB path works with zero manual setup, no root required. |
| 🔧 **Proot on locked-down devices** | If the App's own process domain is blocked from executing proot (SELinux `EACCES`), it auto-falls back to launching proot through the device's `adbd` over the ADB protocol — runs on many more firmwares. |

---

## 📸 Screenshots

<p align="center">
  <img src="screenshots/main-ui.png" alt="TVUbuntu main UI: Ubuntu started, SSH info displayed" width="700"/>
  <br/>
  <sub>Main UI — Ubuntu is running and SSH connection details are shown right on the TV.</sub>
</p>

<p align="center">
  <img src="screenshots/settings-ui.png" alt="TVUbuntu settings UI: Ubuntu version and architecture selection" width="700"/>
  <br/>
  <sub>Settings — choose Ubuntu version, CPU architecture, and enable boot auto-start.</sub>
</p>

---

## 🧱 Tech Stack

| Layer | Choice |
| --- | --- |
| Language | **Kotlin** |
| Build | Gradle 8.5 + Android Gradle Plugin 8.2.2 |
| Min / Target SDK | 21 (Android 5.0) / 34 (Android 14) |
| UI | Material Components + TV-adapted layouts (ViewBinding) |
| Concurrency | Kotlin Coroutines |
| Runtime model | Root `chroot` of `ubuntu-base` (no systemd inside) |
| Service mgmt | `supervisor` (inside Ubuntu) for the web stack |

---

## 📂 Directory Structure

```
TVUbuntu/
├── app/
│   ├── build.gradle.kts                 # App module: minSdk 21, version 1.5.1
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # TV Leanback launcher + boot receiver + ADB accessibility service
│       ├── assets/
│       │   ├── start.sh                 # chroot bootstrap: mount, install SSH, run
│       │   ├── stop.sh                  # teardown: kill sshd, unmount
│       │   ├── start_proot.sh           # proot bootstrap (no-root)
│       │   ├── start_proot_adb.sh       # proot launched via adbd over ADB (locked-down firmwares)
│       │   └── adb_key.pem / adb_cert.pem / adb_key.pub   # baked ADB RSA key pair
│       ├── java/com/ubuntucontroller/
│       │   ├── MainActivity.kt          # TV UI: status, start/stop, SSH card, ADB state
│       │   ├── SettingsActivity.kt      # scripts / SSH / version / arch / ADB config
│       │   ├── UbuntuService.kt         # core logic: rootfs, arch, status, SSH info, ADB host/port
│       │   ├── ShellExecutor.kt         # root shell executor (su auto-detect)
│       │   ├── BootReceiver.kt          # auto-launch on device boot
│       │   ├── AdbClient.kt             # pure-Kotlin ADB client (CNXN/AUTH, RSA via AndroidKeyStore)
│       │   ├── AdbProotService.kt       # proot-over-ADB orchestration (key, push, launch, stop)
│       │   ├── AdbAutoAcquire.kt         # auto-acquire ADB on app launch & boot
│       │   ├── AdbAuthAutoClickService.kt / AdbAccessibilityHelper.kt  # auto-click USB-debug dialogs
│       │   ├── AdbNetworkEnabler.kt     # self-heal net ADB (setprop + restart adbd)
│       │   ├── UbuntuAdbManager.kt / AdbTransport.kt / AdbWirelessPairing.kt / AdbWirelessTransport.kt
│       │   ├── AdbKeyStore.kt           # ADB RSA key in AndroidKeyStore
│       │   └── TvFocusUtils.kt          # D-Pad focus helpers (TV cursor)
│       └── res/                         # layouts, drawables, strings, colors, themes, xml/
├── scripts/                             # gen_adb_pubkey.py / bake_adb_keys.sh (bake App pubkey into firmware)
├── bootstrap_server.sh                  # 🚀 production LEMP-ish stack in one shot
├── build.gradle.kts                     # root project
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat                # Gradle wrapper scripts
└── AGENTS.md                            # project notes for AI agents
```

---

## ⚙️ How It Works

```
App (Kotlin, root)
   │  deployAssets()  → push start.sh / stop.sh to /data/local/ubuntu
   │  ensureRootfs()  → download ubuntu-base-<ver>-<arch>.tar.gz, gzip-decode on device
   ▼
start.sh  (run as root)
   ├─ mount proc/sys/dev/pts into rootfs
   ├─ fix toybox-tar symlinks / hardlinks / setuid bits
   ├─ chroot smoke-test (/bin/bash really runs)
   ├─ chroot → apt install openssh-server (+ curl/git/nano/sudo …)
   ├─ init root password once, enable PermitRootLogin + PasswordAuthentication
   └─ chroot → /usr/sbin/sshd -D -p <port>   (+ three-layer boot: /etc/rc.local → supervisord → /etc/init.d/*)
   ▼
MainActivity shows:  SSH host (device IP) · user · password · port
   ▼
You:  ssh root@<device-ip>   → a real Ubuntu shell 🎉
```

**Engineering highlights (the tricky bits we solved):**

- **Architecture detection that isn't fooled (fixed in 1.2.1).** Emulators like MuMu fake `uname -m` as `aarch64` while the real kernel is `x86_64`; TVUbuntu corrects to `amd64` **only** when the true arch is x86_64 yet `uname` lies. Genuine arm64 / armhf / x86 boxes keep their true arch, so Python/pip inside the chroot resolve the correct platform. Manual override remains available.
- **Three-layer service auto-start (new in 1.2.2).** On every boot `run_ubuntu.sh` launches services in order: (1) your custom `/etc/rc.local`, (2) `supervisord` if installed, (3) a generic `/etc/init.d/*` sweep that auto-discovers and starts any service with an init script — so anything you install (nginx, MySQL, Redis, Baota…) comes up on reboot without editing anything. Dangerous shutdown/mount scripts are skipped for safety.
- **`toybox tar` symlink repair.** Many boxes ship a `tar` that drops symlinks/hardlinks/setuid bits. We rebuild them from the tarball listing so `/bin/bash` and `/lib/ld-linux` resolve correctly.
- **App-side gzip decode.** The box `tar` often can't handle `-z`; the App decodes gzip in Java and passes a plain `.tar` to `tar -xf`.
- **Live progress over a pipe.** `start.sh` emits `UC_PROGRESS|<pct>|<msg>` lines that the App renders as a real progress bar.
- **Safe stop fallback.** If `stop.sh` is missing, the App force-stops via `pkill` + `umount`.

---

## 📥 Installation

### Option A — Sideload the APK (easiest)

1. The box **must be rooted** (Magisk / SuperSU / AOSP `su`).
2. Enable *Settings → Security → Unknown sources* (or the equivalent on your firmware).
3. Install:
   ```bash
   adb install TVUbuntu.apk
   # or copy the APK to the box and open it from a file manager
   ```
4. Open **Ubuntu Controller** from the TV launcher. Grant root when prompted.

> 💡 Grab the latest `TVUbuntu.apk` from the [Releases](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases) page.

### Option B — Build from source

```bash
git clone https://github.com/jiyanlin7113-rgb/TVUbuntu.git
cd TVUbuntu
# Open in Android Studio (recommended), or:
gradle wrapper            # regenerate the Gradle wrapper if needed
./gradlew assembleRelease # → app/build/outputs/apk/release/app-release.apk
```

> 💡 The repo already ships the Gradle wrapper — just run `./gradlew assembleRelease` to build.
> If the wrapper is broken, regenerate it in Android Studio or grab the release APK.

---

## 🕹️ Usage

1. Launch the app on the TV. It detects root and shows the current status.
2. Press **Start Ubuntu**. First launch downloads the rootfs (~28 MB) and installs
   OpenSSH (~40 MB of packages) — this can take a few minutes. Subsequent starts are instant.
3. When status turns green, the **SSH card** shows:
   - **Host** — the box's LAN IP (WiFi preferred, eth0 fallback)
   - **User** — `root`
   - **Password** — default `Aa123456` (change it in Settings)
   - **Port** — `22` (configurable)
4. From your computer:
   ```bash
   ssh root@192.168.1.x      # password: Aa123456
   ```
5. To stop: press **Stop Ubuntu**.

---

## ⚙️ Settings

Open **Settings** on the main screen:

| Setting | Meaning |
| --- | --- |
| Ubuntu version | 22.04 / 24.04 / 26.04 |
| CPU architecture | auto / amd64 / arm64 / armhf (override auto-detection) |
| Runtime mode | Root (chroot) / Proot (no-root) |
| Start/Stop script path | default `/data/local/ubuntu/start.sh` & `stop.sh` |
| SSH port / user / password | connection credentials |
| Auto-start on boot | boot the box straight into Ubuntu |

---

## 🚀 Production Web Stack (optional)

Inside the running Ubuntu, drop in `bootstrap_server.sh` and run it to stand up a full
web stack managed by `supervisor` (no systemd in chroot):

```bash
# from your SSH session inside the box:
bash /root/bootstrap_server.sh
```

It installs & supervises: **nginx** (80) · **PHP-FPM** · **MariaDB** (3306) · **Redis** (6379),
plus common dev tools. Idempotent — safe to re-run.

```bash
supervisorctl status            # see all services
supervisorctl restart nginx     # restart one
mysql -u root                   # MariaDB (unix_socket auth)
```

---

## 🛠️ Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Root denied" | Grant root to the app in Magisk/SuperSU. |
| Download fails | Confirm the box has internet; the App fetches from `cdimage.ubuntu.com`. |
| SSH not ready after start | Tap **Copy Log** and inspect `/data/local/ubuntu/ubuntu.log`. |
| `chroot` smoke-test fails | Usually SELinux; the script already tries `setenforce 0` + `chcon`. Check the log. |
| Emulator shows wrong arch | Set **CPU architecture = amd64** in Settings. |

---

## 📝 Changelog

### v1.5.1
- **Broader device compatibility for Proot — automatic ADB fallback path.** On locked-down firmwares where TVUbuntu's own process domain is blocked from executing proot (SELinux `EACCES` on `app_data_file`), the App now auto-detects the failure and falls back to launching proot through the device's own `adbd` (shell domain) over the ADB protocol. proot binaries/rootfs land in `/data/local/tmp/ubuntu/` (shell-writable/executable), so many more boxes that previously failed outright can now run Ubuntu.
- **Automatic ADB acquisition.** On app launch and on boot, TVUbuntu transparently obtains ADB access — wireless TLS pairing on Android 11+ (no prompt), classic TCP 5555 with an accessibility auto-click fallback for older devices — so the Proot-over-ADB path "just works" without root.
- Added a pure-Kotlin ADB client (`AdbClient.kt`), `AdbProotService.kt` orchestration, `start_proot_adb.sh`, baked-pubkey tooling (`scripts/`), and ADB host/port preferences. Status / stop / console / log now route correctly through either the direct or the ADB path.

### v1.4.4
- **配置页精简（按用户三项改进）：**
  1. **ADB 自动获取（开软件/开机）默认开启** → 从配置页移除该开关 UI，保持常开即可，无需用户干预（底层 `AdbAutoAcquire` 默认 `true` 不变，仅去 UI）。
  2. **删除「显示本机 ADB 公钥」按钮及 `showAdbPubkey()`**：公钥需 root 才能写入 `/data/misc/adb/adb_keys`，而有 root 就走另一条 root 路径，属悖论，故直接去掉；底层 `ensureKey`/`exportPubkey` 因 ADB 连接内部仍依赖而保留不动。
  3. **进配置页初始焦点改放「开启 ADB 授权自动点击」按钮（`btnAdbAutoClick`）**，便于遥控器用户直接开启无障碍自动点击。
  - 同步清理 `strings.xml`（`adb_pubkey_*` 7 条 + `adb_auto_acquire_*` 2 条）、`activity_settings.xml`（删 ADB 公钥区块与自动获取开关区块、重接焦点链 `btnAdbAutoClick ⇄ btnEnableNetAdb ⇄ etAdbHost`）、`SettingsActivity.kt`（删相关监听/import/方法）。
- **Version bump** to `1.4.4` (versionCode 13 → 14)。`clean assembleDebug` 全量编译 BUILD SUCCESSFUL（仅与本次无关的历史警告：deprecated `recycle()`、参数命名、未用参数）。

### v1.4.3
- **彻底移除 Shizuku 集成（按用户要求「不显示、不集成」）：**
  - 删除 `ShizukuBridge.kt`（零依赖反射桥，原本始终处于「未集成」休眠态，仅造成状态行困惑）。
  - `AdbNetworkEnabler` 移除 `enableViaShizuku`；`AdbAutoAcquire` / `AdbProotService` 自愈调用去掉 Shizuku 分支，仅保留 **Root + 已授权 adb** 两条特权来源。
  - `SettingsActivity` 移除 Shizuku 状态行、`授权 Shizuku` 按钮及其 `openShizuku()` 方法与 import。
  - `activity_settings.xml` 删除 Shizuku 区块，并把焦点链重接（`btnEnableNetAdb ⇄ etAdbHost`）。
  - `strings.xml` 删除 `adb_shizuku_*` 四条，并清理 `adb_net_adb_hint` 文案；`build.gradle.kts` / `settings.gradle.kts` / `AndroidManifest.xml` 删除 Shizuku 依赖、rikka 仓库、权限与 provider 注释。
  - 原因：机顶盒走 root / 烘焙公钥 5555 / 经典 ADB 弹窗三件套即可，Shizuku 仅对无 root 无无线调试的手机/平板有意义，且会造成「未集成」状态误导；用户确认不再需要该通道。
- **Version bump** to `1.4.3` (versionCode 12 → 13)。Debug + Release 均 BUILD SUCCESSFUL。

### v1.4.2
- **无 root 设备的 ADB 获取三级递进（按用户明确流程）：**
  1. 开 App（无 root 且「ADB 自动获取」开启）→ `AdbAutoAcquire` 自动获取 ADB；
  2. 自动获取失败 → ADB 按钮显示红「无ADB」，用户手动点击重试（`requestAdbAuth`）；
  3. 手动点击仍连不上（adbd 不可达 / 版本过低，即 `UNREACHABLE`/`ERROR`）→ **自动弹出无线配窗口**（Android 11+）；低版本（无无线调试）给引导提示去开「网络 ADB 调试」或「ADB 授权自动点击」。
- **移除主页面「无线配对」按钮：** 改为由第 3 级自动弹出，焦点链重接（`btnAdb` 与 `btnStart` 相邻）。
- **无线配弹窗改用配置页风格：** `pairing_dialog.xml` 根背景 `bg_primary`、标签 `text_secondary`、输入框 `@drawable/bg_input`（`text_primary`/16sp/52dp/16dp padding）、按钮 `btn_secondary`/`btn_start`（16sp/52dp），并内嵌自定义标题（`text_primary` 20sp 粗体），弹窗窗口背景设为 `bg_primary`。
- **配对地址/端口预填 `127.0.0.1:5555`**（经典网络 ADB 默认），保持可手动修改；「自动发现」仍会覆盖填入。
- **Version bump** to `1.4.2` (versionCode 11 → 12)。Debug + Release 均 BUILD SUCCESSFUL。

### v1.4.1
- **反转无 root 设备的启动优先级（按用户明确流程）：** 之前是「本地 proot 优先 → SELinux 拦截才回退 ADB」；现改为 **「有 root → root 模式；没 root → 先获取 ADB，ADB 通了就经 ADB 启动 Ubuntu；ADB 获取失败才退回本地 proot（最后的路）」**，逻辑与用户的整体目标（ADB 授权只是手段，最终要启动 Ubuntu）一致。
  - **`AdbProotService.acquireTransportForLaunch(context): AdbTransport?`：** 新增同步获取 transport 方法，被启动流程直接复用——含①尽量开启无障碍自动点击、②Android 11+ 无线 TLS 配对直连（免弹窗）优先、③经典 5555 不可达时先 `AdbNetworkEnabler` 自愈再鉴权、④鉴权成功持久化 5555。返回非 null 即已授权可用，null 即获取失败。
  - **`ProotUbuntuService.startUbuntu` 改为 ADB 优先：** 无 root 且「ADB 自动获取」开关开启时，先 `acquireTransportForLaunch` → `launchViaTransport` 经 ADB 推送并常驻启动 proot+SSH；取得 transport 但启动失败、或获取失败，均回退本地 proot。移除原「SELinux 拦截才切 ADB」兜底（因 ADB 已优先）。
  - **开关联动：** 由 `AdbAutoAcquire.isEnabled(context)` 控制（默认开启）。关闭时直接走本地 proot，不再尝试 ADB。
- **Version bump** to `1.4.1` (versionCode 10 → 11)。Debug + Release 均 BUILD SUCCESSFUL。

### v1.4.0
- **ADB 自动获取（开软件 / 开机）— 对齐应用管家「掌控性」补齐：** 用户范围从全志/H618 盒子扩展到手机、平板，要求 ADB 开 App/开机即自动获取，并补齐应用管家的其余掌控能力。
  - **新增 `AdbAutoAcquire` 统一编排器：** 开 App（`MainActivity`）与开机（`AdbBootReceiver` → `BOOT_COMPLETED`）触发。定序：① 尽量以 `WRITE_SECURE_SETTINGS` 直接开启无障碍自动点击；② Android 11+ 优先走无线 TLS 配对（免弹窗，开屏直连）；③ 经典 5555 不可达时先「网络 ADB 自愈」再鉴权；④ 经典通道鉴权成功后把 5555 持久化（`persist.*`），下次自动直连。首次手动授权属正常。
  - **新增 `AdbNetworkEnabler` 网络 ADB 自愈：** 在已获特权 shell（已授权 adb / Root / Shizuku）时 `setprop service.adb.tcp.port 5555` + `persist.adb.tcp.port 5555` + `ctl.restart adbd`（对齐应用管家 `ba/u.java:221`）。
  - **新增 `ShizukuBridge` 手机/平板兜底通道（零依赖反射）：** 编译期不引用 `rikka.shizuku.Shizuku`，故默认即可构建；用户在 `build.gradle.kts` 取消注释 `rikka.shizuku:api`/`:provider` + `settings.gradle.kts` 取消注释 `maven.rikka.app` + 安装 Shizuku App 后，`isAvailable()/exec()/requestPermissionWithListener()` 经反射调用真实 API，提供免 adb 弹窗特权 shell（本沙箱无 `maven.rikka.app`，故依赖默认未启用）。
  - **新增 `AdbBootReceiver`：** `BOOT_COMPLETED` 后后台自动获取 ADB（不启动 Ubuntu），与「软件启动时启动」解耦；仅无 root 且开关开启时触发。
  - **设置页新增：** 「ADB 自动获取（开软件/开机）」开关、「网络 ADB 自愈」按钮、Shizuku 状态与「授权 Shizuku」按钮。
  - **Manifest：** 新增 `RECEIVE_BOOT_COMPLETED` 权限 + `AdbBootReceiver` 接收器 + `moe.shizuku.manager.permission.API_V23` 权限（Shizuku provider 注释占位，启用依赖后取消注释）。
- **Version bump** to `1.4.0` (versionCode 9 → 10)。Debug + Release 均 BUILD SUCCESSFUL。

### v1.3.2
- **补齐 ADB 双通道（对齐应用管家）:** 应用管家并非全程无弹窗——它还有「经典网络 ADB（TCP 5555，RSA 握手）」回退通道，靠 `AccService` 无障碍服务自动点掉「允许 USB 调试」系统弹窗。TVUbuntu 此前只实现了 Android 11+ 无线调试 TLS 配对（无弹窗），对 Android 11 以下 / 无配对码功能的盒子（如 H618、全志）会卡在授权。现补齐：
  - **新增 `AdbAuthAutoClickService`（无障碍服务）:** 监听窗口变化，命中 `com.android.systemui` / `com.android.settings` 内含「USB 调试 / 无线调试 / OTG / USB 设备」关键字的弹窗，自动点「允许」。保守匹配，不会误点其它弹窗。配置见 `res/xml/adb_auth_accessibility.xml`。
  - **设置页「ADB 授权自动点击」开关:** 优先以 `WRITE_SECURE_SETTINGS` 直接开启，失败则跳转系统无障碍设置引导手动开启。
  - **经典 5555 通道回退:** `AdbProotService` 在无线配对不可用（Android < 11 或 `AdbPairingRequiredException`）时自动走经典 TCP 握手，弹窗由无障碍服务自动授权（`AdbClient` 已发 `AUTH_PUBKEY` 触发）。
  - **固件烘焙公钥脚本:** `scripts/gen_adb_pubkey.py` 生成 `assets/adb_key.pub`（adb_keys 格式，与运行期算法一致），`scripts/bake_adb_keys.sh` 写入 `/data/misc/adb/adb_keys` 与 `/adb_keys` 并修正权限/SELinux。烘焙后经典通道零弹窗，无需无障碍权限。
- **Version bump** to `1.3.2` (versionCode 8 → 9)。

### v1.3.1
- **Version bump** to `1.3.1` (versionCode 5 → 8); prior minor iterations had not kept versionCode in sync.
- **无线调试免弹窗配对（Proot/ADB 通道）:** 新增「无线配对」按钮与支持库 `muntashirakon/adb`（libadb-android 3.1.1）。流程：mDNS 发现 `_adb-tls-pairing` → 输入 6 位配对码静默授权本机公钥（写入设备 `adb_keys`，**无「允许 USB 调试」弹窗**）→ 经 `_adb-tls-connect` 建 TLS 长连接。开屏若已配对过会自动 TLS 直连。私钥与自签证书内置 `assets/adb_key.pem` + `adb_cert.pem`（同一把 RSA 2048 密钥，公钥恒定，旧的「烘焙进固件」方案依旧有效）。传统 5555 明文通道保留为回退。
- **Low-port note (Proot mode):** Proot mode runs *unprivileged* — Android restricts non-root processes to binding ports **≥ 1024** (`net.ipv4.ip_unprivileged_port_start=1024`). So nginx/Baota (aaPanel) listening on `888` fails with `bind() to 0.0.0.0:888 failed (13: Permission denied)` — that is an Android kernel limit, **not** an app-level port block (the app's own SSH daemon uses 8022 for this reason). Fix: use a port ≥ 1024 (e.g. `listen 8088`) or run in Root mode.
- **Arch & Android version coverage confirmed:** detection uses `getprop ro.product.cpu.abilist` + `uname -m` (handles x86_64, MuMu faked-aarch64 correction, aarch64, armv7/armv8); `minSdk 21` (Android 5.0) + `targetSdk 34` (Android 14); all three ABI libproot.so already carry the empty-path fix — emulator and real device behave the same.

### v1.2.3
- **Built-in command console.** The main screen now includes a live command terminal card. It streams shell output directly on the TV, so you can run quick checks like `cat /etc/os-release` or `df -h` without opening an SSH client.
- **Bug fixes and stability improvements.** Polished edge cases in the shell executor and startup flow.

### v1.2.2
- **Three-layer service auto-start.** `run_ubuntu.sh` now runs services in order on every boot: `/etc/rc.local` (your custom commands, kept for backward compatibility) → `supervisord` (if installed) → a generic `/etc/init.d/*` sweep that auto-discovers and starts any service exposing an init script. New services boot automatically on reboot with zero config changes; dangerous system scripts (halt/reboot/mount/…) are skipped for safety.

### v1.2.1
- **Fixed real-environment architecture detection.** `rootfsArch()` now treats `uname -m` as the baseline and overrides to `amd64` **only** when the true arch is x86_64 but `uname` was spoofed (e.g. MuMu emulator masking itself as `aarch64`). Genuine arm64 / armhf / x86 devices keep their true architecture, so Python/pip inside the chroot read the correct value.

---

## 📜 License

[MIT](LICENSE) © 2026 Jiyan Daxia / jiyanlin7113-rgb. Free for personal and commercial use.
Note: running a chroot requires root and is at your own risk — it may void warranties
and can brick misconfigured devices.

---

## ☕ Like it?

If TVUbuntu gave your old TV box a new life, **⭐ star the repo** and share it!
Pull requests and issue reports are very welcome. 🐧
