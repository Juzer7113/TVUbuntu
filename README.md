# 📺🐧 TVUbuntu — Run a Real Ubuntu on Your Android TV Box

> Turn any Android TV box or Android device into a tiny, always-on **Ubuntu server**.
> A Kotlin Android **TV** app boots a genuine Ubuntu (22.04 / 24.04 / 26.04) via a native **chroot**, auto-installs **OpenSSH**, and shows the SSH connection info right on your living-room screen.

[![Platform](https://img.shields.io/badge/platform-Android%20TV-1A1A2E?logo=android&logoColor=white)](https://www.android.com/tv/)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/min%20SDK-21%20(Android%205.0)-34A853)](https://developer.android.com/about/versions)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![APK](https://img.shields.io/badge/APK-TVUbuntu%201.1.0-orange)](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases)

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
| 🐧 **Native chroot** | Uses the kernel-native `chroot` — no `proot` binary, lighter and faster. |
| 📡 **Auto rootfs download** | App downloads the minimal `ubuntu-base` tarball and decodes gzip on-device (avoids the box's `toybox tar` quirks). |
| 🔑 **Zero-config SSH** | Installs & launches `openssh-server`, then displays host / user / password / port on the TV. |
| 🎮 **TV-remote UI** | D-Pad navigation, focus-scaling "cursor" animation, large high-contrast buttons — built for a remote, not a touchscreen. |
| 🌐 **Multi-architecture** | `arm64` (real boxes), `amd64` (x86_64 emulators like MuMu), `armhf` (32-bit ARM). |
| 🔄 **Multi-version** | Ubuntu **22.04** (jammy) / **24.04** (noble) / **26.04** (resolute), isolated per version+arch. |
| ⚙️ **Boot auto-start** | Optional "start Ubuntu on boot" + launch-on-boot receiver. |
| 🛡️ **Robust shell layer** | `ShellExecutor` auto-detects `su` modes (interactive vs `su -c`), streams live progress, survives large output. |
| 🚀 **Production web stack** | One-shot `bootstrap_server.sh` spins up **nginx + PHP-FPM + MariaDB + Redis** under `supervisor`. |

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
│   ├── build.gradle.kts                 # App module: minSdk 21, version 1.1.0
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # TV Leanback launcher + boot receiver
│       ├── assets/
│       │   ├── start.sh                 # chroot bootstrap: mount, install SSH, run
│       │   └── stop.sh                  # teardown: kill sshd, unmount
│       ├── java/com/ubuntucontroller/
│       │   ├── MainActivity.kt          # TV UI: status, start/stop, SSH card
│       │   ├── SettingsActivity.kt      # scripts / SSH / version / arch config
│       │   ├── UbuntuService.kt         # core logic: rootfs, arch, status, SSH info
│       │   ├── ShellExecutor.kt         # root shell executor (su auto-detect)
│       │   ├── BootReceiver.kt          # auto-launch on device boot
│       │   └── TvFocusUtils.kt          # D-Pad focus helpers (TV cursor)
│       └── res/                         # layouts, drawables, strings, colors, themes
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
   └─ chroot → /usr/sbin/sshd -D -p <port>   (+ optional /etc/rc.local, supervisord)
   ▼
MainActivity shows:  SSH host (device IP) · user · password · port
   ▼
You:  ssh root@<device-ip>   → a real Ubuntu shell 🎉
```

**Engineering highlights (the tricky bits we solved):**

- **Architecture detection that isn't fooled.** Emulators like MuMu fake `uname -m` as `aarch64` while the real kernel is `x86_64`. TVUbuntu trusts `getprop ro.product.cpu.abilist` first, and lets you override manually.
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
4. Open **Ubuntu 控制器** from the TV launcher. Grant root when prompted.

> 💡 Grab the latest `TVUbuntu.apk` from the [Releases](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases) page.

### Option B — Build from source

```bash
git clone https://github.com/jiyanlin7113-rgb/TVUbuntu.git
cd TVUbuntu
# Open in Android Studio (recommended), or:
gradle wrapper            # regenerate the Gradle wrapper if needed
./gradlew assembleRelease # → app/build/outputs/apk/release/app-release.apk
```

> ⚠️ The repo ships **without** the Gradle wrapper JAR (binary, not text-committable here).
> The release APK is prebuilt, or use Android Studio which supplies its own Gradle.

---

## 🕹️ Usage

1. Launch the app on the TV. It detects root and shows the current status.
2. Press **启动 Ubuntu** (Start). First launch downloads the rootfs (~28 MB) and installs
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
5. To stop: press **停止 Ubuntu** (Stop).

---

## ⚙️ Settings

Open **配置** (Settings) on the main screen:

| Setting | Meaning |
| --- | --- |
| Ubuntu 版本 | 22.04 / 24.04 / 26.04 |
| CPU 架构 | auto / amd64 / arm64 / armhf (override auto-detection) |
| 启动/停止脚本路径 | default `/data/local/ubuntu/start.sh` & `stop.sh` |
| SSH 端口 / 用户名 / 密码 | connection credentials |
| 开机自动启动 | boot the box straight into Ubuntu |

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
| "Root 未授权" | Grant root to the app in Magisk/SuperSU. |
| Download fails | Confirm the box has internet; the App fetches from `cdimage.ubuntu.com`. |
| SSH not ready after start | Tap **复制日志** (Copy Log) and inspect `/data/local/ubuntu/ubuntu.log`. |
| `chroot` smoke-test fails | Usually SELinux; the script already tries `setenforce 0` + `chcon`. Check the log. |
| Emulator shows wrong arch | Set **CPU 架构 = amd64** in Settings. |

---

## 📜 License

[MIT](LICENSE) © 2026 吉大大侠 / jiyanlin7113-rgb. Free for personal and commercial use.
Note: running a chroot requires root and is at your own risk — it may void warranties
and can brick misconfigured devices.

---

## ☕ Like it?

If TVUbuntu gave your old TV box a new life, **⭐ star the repo** and share it!
Pull requests and issue reports are very welcome. 🐧
