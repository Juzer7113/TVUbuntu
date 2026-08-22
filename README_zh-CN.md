# 📺🐧 TVUbuntu —— 在安卓电视盒子上跑一个真正的 Ubuntu

> 把任意一台吃灰的安卓电视盒子或安卓设备，变成一台常驻运行的 **Ubuntu 服务器**。
> 这是一个用 Kotlin 编写的安卓 **TV** 应用：通过原生的 **chroot** 启动真正的 Ubuntu（22.04 / 24.04 / 26.04），
> 自动安装 **OpenSSH**，并直接在大屏幕上显示 SSH 连接信息。

[![平台](https://img.shields.io/badge/平台-Android%20TV-1A1A2E?logo=android&logoColor=white)](https://www.android.com/tv/)
[![语言](https://img.shields.io/badge/语言-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![最低 SDK](https://img.shields.io/badge/最低%20SDK-21%20(Android%205.0)-34A853)](https://developer.android.com/about/versions)
[![许可证](https://img.shields.io/badge/许可证-MIT-green.svg)](LICENSE)
[![APK](https://img.shields.io/badge/APK-TVUbuntu%201.5.1-orange)](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases)

---

## 🌟 为什么要做 TVUbuntu？

大多数电视盒子都是**被浪费的算力**——常年闲置，却跑着一堆广告启动器。
TVUbuntu 让这些硬件「发挥余热」，变成一台**真正的 Linux 服务器**：

- 🐧 **货真价实的 Ubuntu 用户态**，不是容器模拟的 shell。可以 `apt` 装包、编译代码、托管服务。
- 💻 **用笔记本 SSH 连进去**——体验和局域网里任何其他无头 Ubuntu 主机一模一样。
- 🔌 **常驻、低功耗**——电视盒子功耗约 2–3 W，可 7×24 小时当作家庭实验室节点。
- 💰 **盘活手中已有设备**——不必再买树莓派或租用云服务器。

```
┌─────────────────────────┐         SSH (端口 22)         ┌──────────────────────────┐
│   你的笔记本 / 电脑      │ ───────────────────────────▶ │   安卓电视盒子 (已 Root)   │
│   $ ssh root@192.168.x.x│                              │  ┌──────────────────────┐ │
│                         │                              │  │ Ubuntu chroot        │ │
│                         │                              │  │  ├─ openssh-server   │ │
│                         │                              │  │  ├─ nginx / PHP / 数据库│ │
│                         │                              │  │  └─ 你的各种服务      │ │
│                         │                              │  └──────────────────────┘ │
└─────────────────────────┘                              └──────────────────────────┘
        ▲                                                          │
        │          TVUbuntu 应用在大屏显示 主机 / 用户 / 密码         │
        └──────────────────────────────────────────────────────────┘
```

---

## ✨ 功能特性

| 特性 | 说明 |
| --- | --- |
| 🐧 **原生 chroot（Root 模式）** | 在 Root 模式下，使用内核原生的 `chroot`，无需 `proot` 二进制，更轻量、更快。 |
| 📡 **自动下载 rootfs** | 应用自动下载最小化的 `ubuntu-base` 压缩包，并在设备端完成 gzip 解压（绕开盒子自带 `toybox tar` 的坑）。 |
| 🔑 **零配置 SSH** | 自动安装并启动 `openssh-server`，随后在大屏展示 主机 / 用户 / 密码 / 端口。 |
| 🎮 **TV 遥控器 UI** | 方向键（D-Pad）导航、焦点放大「光标」动画、大号高对比度按钮——专为遥控器而非触屏设计。 |
| 🌐 **多架构支持** | `arm64`（真机盒子）、`amd64`（x86_64 模拟器如 MuMu）、`armhf`（32 位 ARM）。 |
| 🔄 **多版本支持** | Ubuntu **22.04**（jammy）/ **24.04**（noble）/ **26.04**（resolute），按版本+架构隔离。 |
| ⚙️ **开机自启** | 可选「开机自动启动 Ubuntu」+ 开机广播接收器自动拉起。 |
| 🛡️ **稳健的 Shell 层** | `ShellExecutor` 自动探测 `su` 模式（交互式 vs `su -c`），流式实时进度，可承受大输出不卡死。 |
| 🚀 **生产级 Web 栈** | 一条命令的 `bootstrap_server.sh` 在 `supervisor` 下拉起 **nginx + PHP-FPM + MariaDB + Redis**。 |
| 🖥️ **命令控制台** | 主界面新增实时命令终端卡片，可直接在电视上查看 `cat /etc/os-release`、`df -h` 等输出，无需离开电视去开 SSH 客户端。 |
| 🌱 **免 Root 的 Proot 模式** | 没有 Root？改用 `proot` 运行 Ubuntu——同样是真正的 Ubuntu 用户态，只是无法使用特权端口（<1024）和 systemd。适合被锁死或没 Root 的盒子。 |
| 📡 **ADB 自动获取** | 启动应用与开机时静默获取 ADB 权限（Android 11+ 走免弹窗无线 TLS 配对，经典 5555 通道由无障碍自动点击兜底）——「经 ADB 跑 Proot」零配置即可用，无需 Root。 |
| 🔧 **收紧固件也能跑 Proot** | 当 App 自身进程域被 SELinux 禁止直 exec proot（`EACCES`）时，自动改走「经设备 adbd 以 ADB 协议拉起 proot」的回退路径，兼容更多固件。 |

---

## 📸 截图

<p align="center">
  <img src="screenshots/main-ui.png" alt="TVUbuntu 主界面：Ubuntu 已启动并显示 SSH 连接信息" width="700"/>
  <br/>
  <sub>主界面 — Ubuntu 正在运行，大屏直接显示 SSH 主机 / 用户 / 密码 / 端口。</sub>
</p>

<p align="center">
  <img src="screenshots/settings-ui.png" alt="TVUbuntu 设置界面：选择 Ubuntu 版本与 CPU 架构" width="700"/>
  <br/>
  <sub>设置界面 — 选择 Ubuntu 版本、CPU 架构，并开启开机自启动。</sub>
</p>

---

## 🧱 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | **Kotlin** |
| 构建 | Gradle 8.5 + Android Gradle Plugin 8.2.2 |
| 最低 / 目标 SDK | 21 (Android 5.0) / 34 (Android 14) |
| UI | Material Components + TV 适配布局（ViewBinding） |
| 并发 | Kotlin Coroutines |
| 运行模型 | Ubuntu-base 的 root `chroot`（内部无 systemd） |
| 服务管理 | `supervisor`（Ubuntu 内部）管理 Web 栈 |

---

## 🔓 Proot 模式（无 Root）

不想 Root 盒子也能跑？TVUbuntu 内置 **Proot 用户空间方案**：无需 `su`、不调用 `mount`/`chroot`，
全部在应用沙盒内用 `libproot.so` 完成，适合无法 Root 或不想 Root 的设备。

| 项目 | 说明 |
| --- | --- |
| SSH 服务 | **Dropbear**（OpenSSH 8.x 的特权分离子进程会触发本 proot 构建的 `openat(AT_EMPTY_PATH)` 断言崩溃，故改用对 proot 友好的 Dropbear；OpenSSH 仍保留以复用 sftp-server 与客户端工具） |
| 默认端口 | `8022`（≥1024，无需 Root 绑定） |
| 启动脚本 | `assets/start_proot.sh` → 解包 rootfs → `install_ssh_proot.sh` 装包 → `run_ubuntu_proot.sh` 拉起 dropbear 看门狗 |
| 停止脚本 | `assets/stop_proot.sh`（不调用 `umount`，只杀 proot 进程树并释放端口） |
| 限制 | 需透传宿主 `/proc` 供 `ps`/`htop` 使用（代价是容器内可读宿主进程 cmdline，proot 通病，无法经此杀宿主进程） |

> 在「配置」里把**运行模式**设为 `Proot`，或在 `Auto` 模式下未 Root 时自动回退 Proot。

## 📂 目录结构

```
TVUbuntu/
├── app/
│   ├── build.gradle.kts                 # App 模块：minSdk 21，版本 1.5.1
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # TV Leanback 启动器 + 开机接收器 + ADB 无障碍服务
│       ├── assets/
│       │   ├── start.sh                 # chroot 引导：挂载、装 SSH、运行
│       │   ├── stop.sh                  # 收尾：杀掉 sshd、卸载挂载
│       │   ├── start_proot.sh           # proot 引导（免 Root）
│       │   ├── start_proot_adb.sh       # 经 adbd 以 ADB 拉起 proot（收紧固件）
│       │   └── adb_key.pem / adb_cert.pem / adb_key.pub   # 内置 ADB RSA 密钥对
│       ├── java/com/ubuntucontroller/
│       │   ├── MainActivity.kt          # TV 主界面：状态、启停、SSH 卡片、ADB 状态
│       │   ├── SettingsActivity.kt      # 脚本 / SSH / 版本 / 架构 / ADB 配置
│       │   ├── UbuntuService.kt         # 核心逻辑：rootfs、架构、状态、SSH 信息、ADB 主机/端口
│       │   ├── ShellExecutor.kt         # root shell 执行器（自动探测 su 模式）
│       │   ├── BootReceiver.kt          # 设备开机后自动拉起
│       │   ├── AdbClient.kt             # 纯 Kotlin ADB 客户端（CNXN/AUTH，RSA 由 AndroidKeyStore 签名）
│       │   ├── AdbProotService.kt       # 经 ADB 跑 proot 的编排（密钥、推送、启动、停止）
│       │   ├── AdbAutoAcquire.kt         # 开软件/开机自动获取 ADB
│       │   ├── AdbAuthAutoClickService.kt / AdbAccessibilityHelper.kt  # 自动点掉 USB 调试弹窗
│       │   ├── AdbNetworkEnabler.kt     # 网络 ADB 自愈（setprop + 重启 adbd）
│       │   ├── UbuntuAdbManager.kt / AdbTransport.kt / AdbWirelessPairing.kt / AdbWirelessTransport.kt
│       │   ├── AdbKeyStore.kt           # AndroidKeyStore 中的 ADB RSA 密钥
│       │   └── TvFocusUtils.kt          # 方向键焦点辅助（TV 光标）
│       └── res/                         # 布局、图标、字符串、颜色、主题、xml/
├── scripts/                             # gen_adb_pubkey.py / bake_adb_keys.sh（把 App 公钥烘焙进固件）
├── bootstrap_server.sh                  # 🚀 一条命令搭建生产级 Web 栈
├── build.gradle.kts                     # 根工程
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat                # Gradle 包装脚本
└── AGENTS.md                            # 面向 AI 代理的项目说明
```

---

## ⚙️ 工作原理

```
App（Kotlin，root 权限）
   │  deployAssets()  → 把 start.sh / stop.sh 推到 /data/local/ubuntu
   │  ensureRootfs()  → 下载 ubuntu-base-<版本>-<架构>.tar.gz，设备端解 gzip
   ▼
start.sh（以 root 运行）
   ├─ 把 proc/sys/dev/pts 挂载进 rootfs
   ├─ 修复 toybox tar 丢失的符号链接 / 硬链接 / setuid 位
   ├─ chroot 冒烟测试（确认 /bin/bash 真的能跑起来）
   ├─ chroot 内 → apt 安装 openssh-server（+ curl/git/nano/sudo …）
   ├─ 仅首次初始化 root 密码，开启 PermitRootLogin + PasswordAuthentication
   └─ chroot 内 → /usr/sbin/sshd -D -p <端口>   （+ 三层自启动：/etc/rc.local → supervisord → /etc/init.d/*）
   ▼
MainActivity 展示：  SSH 主机（设备 IP）· 用户 · 密码 · 端口
   ▼
你：  ssh root@<设备IP>   →  一个真正的 Ubuntu shell 🎉
```

**工程亮点（我们踩过的坑）：**

- **不会被骗的架构识别（1.2.1 修复）。** MuMu 等模拟器会把 `uname -m` 伪装成 `aarch64`，但真实内核是 `x86_64`；
  TVUbuntu **仅当**真实架构是 x86_64 而 `uname` 被伪装时，才纠正为 `amd64`。真 arm64 / armhf / x86 设备保留真实架构，
  因此 chroot 内的 Python/pip 能读到正确平台。设置里仍可手动覆盖。
- **三层服务自启动（1.2.2 新增）。** 每次开机 `run_ubuntu.sh` 依次拉起服务：(1) 你自定义的 `/etc/rc.local`，(2) 若装了 `supervisord` 则用它接管，(3) 通用 `/etc/init.d/*` 遍历——自动发现并启动任何带 init 脚本的服务。
  也就是说，你之后装的 nginx / MySQL / Redis / 宝塔 等，重启后会自动起来，无需改动任何脚本。危险的关机/挂载类脚本会被自动跳过，保证安全。
- **`toybox tar` 符号链接修复。** 很多盒子的 `tar` 会丢掉符号链接 / 硬链接 / setuid 位。
  我们从 tar 包清单里重建它们，确保 `/bin/bash` 和 `/lib/ld-linux` 能正确解析。
- **应用端解 gzip。** 盒子的 `tar` 常不支持 `-z`；应用用 Java 先把 gzip 解成纯 `.tar`，再交给 `tar -xf`。
- **通过管道实时进度。** `start.sh` 输出 `UC_PROGRESS|<百分比>|<说明>` 行，应用渲染成真实进度条。
- **安全的停止兜底。** 若 `stop.sh` 缺失，应用通过 `pkill` + `umount` 强制停止。

---

## 📥 安装

### 方式 A —— 直接侧载 APK（最简单）

1. 盒子**必须已 Root**（Magisk / SuperSU / AOSP `su`）。
2. 开启 *设置 → 安全 → 未知来源*（不同固件位置可能不同）。
3. 安装：
   ```bash
   adb install TVUbuntu.apk
   # 或者把 APK 拷到盒子上，用文件管理器打开安装
   ```
4. 从 TV 启动器中打开 **Ubuntu 控制器**，按提示授予 Root 权限。

> 💡 最新 `TVUbuntu.apk` 请到 [Releases](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases) 页面下载。

### 方式 B —— 从源码构建

```bash
git clone https://github.com/jiyanlin7113-rgb/TVUbuntu.git
cd TVUbuntu
# 推荐直接用 Android Studio 打开，或者：
gradle wrapper            # 如需要可重新生成 Gradle 包装
./gradlew assembleRelease # → app/build/outputs/apk/release/app-release.apk
```

> ⚠️ 本仓库**不含** Gradle 包装 JAR（二进制，无法以文本形式提交）。
> 发布版的 APK 已预先构建好，或使用 Android Studio（它会自带 Gradle）。

---

## 🕹️ 使用

1. 在电视上启动应用，它会检测 Root 并显示当前状态。
2. 按 **启动 Ubuntu**。首次启动会下载 rootfs（约 28 MB）并安装 OpenSSH（约 40 MB 依赖包），
   可能需要几分钟；之后的启动是秒级的。
3. 状态变绿后，**SSH 卡片**会显示：
   - **主机** —— 盒子的局域网 IP（优先 WiFi，回退 eth0）
   - **用户** —— `root`
   - **密码** —— 默认 `Aa123456`（可在设置里修改）
   - **端口** —— `22`（可配置）
4. 在你的电脑上：
   ```bash
   ssh root@192.168.1.x      # 密码：Aa123456
   ```
5. 停止：按 **停止 Ubuntu**。

---

## ⚙️ 设置

在主界面打开 **配置**：

| 设置项 | 含义 |
| --- | --- |
| Ubuntu 版本 | 22.04 / 24.04 / 26.04 |
| CPU 架构 | auto / amd64 / arm64 / armhf（覆盖自动识别） |
| 运行模式 | Root（chroot）/ Proot（免 Root） |
| 启动/停止脚本路径 | 默认 `/data/local/ubuntu/start.sh` 与 `stop.sh` |
| SSH 端口 / 用户名 / 密码 | 连接凭据 |
| 开机自动启动 | 盒子开机直接进入 Ubuntu |

---

## 🚀 生产级 Web 栈（可选）

在已运行的 Ubuntu 里放入 `bootstrap_server.sh` 并执行，即可在 `supervisor` 管理下
（chroot 内无 systemd）一键拉起完整 Web 栈：

```bash
# 在盒子的 SSH 会话里执行：
bash /root/bootstrap_server.sh
```

它会安装并托管：**nginx**（80）· **PHP-FPM** · **MariaDB**（3306）· **Redis**（6379），
外加常用开发工具。脚本**幂等**，可重复运行。

```bash
supervisorctl status            # 查看所有服务
supervisorctl restart nginx     # 重启单个服务
mysql -u root                   # MariaDB（unix_socket 认证）
```

---

## 🛠️ 常见问题排查

| 现象 | 解决办法 |
| --- | --- |
| 提示「Root 未授权」 | 在 Magisk / SuperSU 里给本应用授予 Root 权限。 |
| 下载失败 | 确认盒子已联网；应用从 `cdimage.ubuntu.com` 拉取镜像。 |
| 启动后 SSH 未就绪 | 点 **复制日志**，查看 `/data/local/ubuntu/ubuntu.log`。 |
| `chroot` 冒烟测试失败 | 多为 SELinux 问题；脚本已尝试 `setenforce 0` + `chcon`，详见日志。 |
| 模拟器架构识别错 | 在设置里把 **CPU 架构** 设为 `amd64`。 |

---

## 📝 更新日志

### v1.5.1（ADB 获取 + 兼容更多设备的 Proot）

- **Proot 兼容更多设备 —— 新增 ADB 回退路径。** 在收紧固件上，App 自身进程域因 SELinux（`app_data_file` 的 `EACCES`）被禁止直 exec proot；TVUbuntu 现在会在直 exec 失败后，自动改走「经设备自带 adbd（shell 域）以 ADB 协议拉起 proot」的路径，proot 二进制与 rootfs 落在 `/data/local/tmp/ubuntu/`（shell 域可写可执行），从而让很多此前直接失败的设备也能跑起来。
- **ADB 自动获取（开软件/开机）。** 启动应用与开机时，App 自动获取 ADB 权限——Android 11+ 走免弹窗无线 TLS 配对，经典 5555 通道由无障碍自动点击兜底——无需 Root 即可让「经 ADB 跑 Proot」开箱即用。
- 新增纯 Kotlin ADB 客户端（`AdbClient.kt`）、`AdbProotService.kt` 编排、`start_proot_adb.sh`、公钥烘焙工具（`scripts/`）、ADB 主机/端口偏好；状态/停止/终端/日志均可在直 exec 与 ADB 两条路径间正确路由。

### v1.3.1（版本号升级 + 低端口说明）
- **版本号升级**：`1.2.3` → `1.3.1`（versionCode 5 → 8）。此前多个版本迭代未同步 versionCode，本次一并修正。
- **Proot 模式低端口说明（888 端口 Permission denied）**：proot 模式**无 root 权限**，Android 系统默认限制非特权进程只能绑定 **1024 以上**端口（`net.ipv4.ip_unprivileged_port_start=1024`）。因此 nginx/宝塔等监听 `888`（<1024）时内核直接拒绝：`bind() to 0.0.0.0:888 failed (13: Permission denied)`——**这不是 App 禁用端口，是 Android 系统限制**（App 内置的 SSH 服务因此默认使用 8022）。解决：把 nginx 监听端口改为 ≥1024（如 `listen 8088`），或改用 root 模式运行。
- **架构判断与安卓版本兼容**：架构自动探测基于 `getprop ro.product.cpu.abilist` + `uname -m`，覆盖 x86_64（含 MuMu 伪装 aarch64 的纠正）/ aarch64 / armv7 / armv8 全部主流情况；`minSdk 21`（Android 5.0）+ `targetSdk 34`（Android 14）覆盖主流安卓版本；三个 ABI（x86_64 / arm64-v8a / armeabi-v7a）的 `libproot.so` 均已打空路径补丁，模拟器与真机行为一致。

### v1.2.8（修复 26.04 Proot 安装崩溃根因 —— 空路径断言）
- **根因级修复（不再靠概率/绕行）**：26.04 proot 模式安装时 `systemd` postinst 与 OpenSSH 8.x 特权分离子进程会发出 `openat(dirfd, "", AT_EMPTY_PATH)` 空路径调用，而 proot 的 `path.c compare_paths2()` 在空路径容错分支**之前**有 `assert(length1>0)`/`assert(length2>0)`，直接 SIGABRT 整容器退出（`assertion "length2 > 0" failed`）。此前只能靠「先 root 模式装好再切 proot」或反复重试碰运气。
- **修复方式**：已对三个架构（x86_64 / arm64-v8a / armeabi-v7a）的 `libproot.so` 打**二进制补丁**——把空路径检查跳转到 `PATHS_ARE_NOT_COMPARABLE` 返回路径（保留 `je`/`cbz` 条件语义，等长替换，不重编译、不破坏偏移）。与源码删 assert 的修复语义完全等价。
- **兼容性**：22.04 / 24.04 / 26.04 全部受益；同时消除 OpenSSH 8.x 特权分离子进程的空路径崩溃隐患。
- 说明：原始 .so 已备份至 `libproot_backup/`，可随时回滚；源码级补丁 `proot_empty_path_assert_fix.patch` 一并留存备查。

### v1.2.7（支持 Ubuntu 26.04 Proot 模式）
- **修复 26.04 安装失败（uutils coreutils 与 proot 不兼容）**：26.04 的 coreutils 换成 Rust 版 multi-call（`/usr/bin/coreutils`，各工具是指向它的 symlink，靠 argv[0] 分发子命令）。proot 用自定义 loader 加载程序时会把 argv[0] 改写成临时文件（`prooted-*`），uutils 认不出即报 `coreutils: unknown program 'prooted-*'`，导致 `mkdir`/`rm`/`ls` 等全部失效、apt 拉不到任何包。修复：首次安装（26.04）时自动从 Ubuntu 仓库下载 **GNU 独立版 coreutils**（`/usr/bin/gnu*`，不依赖 argv[0]）+ `libcap2`，bind 进 rootfs 后由安装脚本解包，并把 `/usr/bin` 下指向 uutils 的 symlink 全部替换为 `gnu*` 独立版（依赖核对：仅需 `libc` + `libselinux`，26.04 base 均已内置）。
- 说明：22.04 / 24.04 的 coreutils 仍是传统多文件二进制，不受影响。

### v1.2.6（界面文案与自启语义调整）
- **主页顶部徽章**：`Proot 模式（无 Root）` → `Proot 模式`。
- **服务状态文案**：去除所有 `（proot 模式）` 后缀（如 `Ubuntu 启动成功`、`Ubuntu 运行中`、`Ubuntu 未运行`），状态卡只保留纯状态文本。
- **主机地址带端口**：SSH 信息卡主机地址显示 `IP:端口`（如 `172.16.2.31:8022`），root 模式为 `IP:22`。
- **运行模式文案**：`强制 Root` → `Root 模式`；`强制 Proot（无 Root）` → `Proot 模式`。
- **自启语义调整**：`开机自动启动 Ubuntu` → `软件启动时启动 Ubuntu`，开关改为紧贴文字右侧；逻辑同步改为「打开 App 时按开关决定是否自动启动」，**废弃系统开机广播自启**（BootReceiver/BootService 已从 Manifest 移除注册，不再监听开机广播，消除「未勾选却开机自启」的来源）。
- **内置终端修复**：直连 proot 漏设 `PROOT_TMP_DIR` 导致 `can't canonicalize /tmp`、命令执行失败的问题，已与启动脚本临时目录保持一致。
- **修复脚本换行符事故**：Windows 下 `git core.autocrlf=true` 会把 `assets/*.sh` 检出成 CRLF 行尾，Android 的 mksh 不识别 `\r`（报 `set: -:unknown option`，启动直接失败）。已将全部 6 个 shell 脚本强制转回 LF，并新增 `.gitattributes`（`*.sh text eol=lf`）防止再次被转换；若在 Windows 上手工编辑这些脚本，请将编辑器行尾设为 LF。

### v1.2.5（修复重复启动与状态误判）
- **修复「安装期间启动按钮复活」**：首次安装（约 2-3 分钟）期间，App 状态探测曾误判为「未运行」，`onResume` 刷新会把「启动」按钮重新点亮，导致用户再按一次或自动启动逻辑再次触发，出现日志里的 `已有启动实例...跳过重复启动`。现在状态机新增「安装/启动中」维度：安装期间按钮整体禁用、不再自动拉起第二条启动实例。
- **防重入锁升级为原子锁**：`mkdir` 原子互斥（旧文件锁「先查后写」存在竞态，并发启动可能双双通过检查）；锁内记录 PID，校验其 cmdline 确为本脚本，防 PID 复用导致的误跳过；实例强杀/崩溃残留的锁会自动清理。
- **跳过提示不再误导**：被拦截的重复实例改报「正在安装/启动中（已有实例进行中）」，不再误报「100% 已启动」。
- **修正回归（X11 开关）**：Dropbear 2020.81 无 `-x` 选项，此前误加导致启动即退出；已移除并补充说明（详见上文 X11 提示更正）。

### v1.2.4（Proot 模式稳定性修复）
- **Proot 模式 SSH 探测/校验修复**：实际 SSH 服务是 **Dropbear**，旧逻辑误探测 `sshd` 进程与 `/usr/sbin/sshd` 二进制，在缺少 `ss`/`netstat` 的精简固件上会误报「SSH 未就绪 / 安装异常」。现已统一改为探测 dropbear。
- **修复停止脚本**：兜底杀进程条件（`rootfs.*` 且 `run_ubuntu_proot.sh` 无进程可同时命中）改为按 rootfs 路径 + dropbear 精确清理，避免停止失效、端口/进程残留；并清理启动锁。
- **修复并发自启**：开机广播（BootService）与打开 App（MainActivity）可能同时触发启动，新增启动锁 + 进程/端口三重防重入，避免重复解包、抢端口。
- **消除登录告警**：动态补齐容器内 `/etc/group` 缺失的宿主补充组 gid，消除 `groups: cannot find name for group ID ...`。
- **X11 转发提示说明（重要更正）**：`X11 forwarding request failed on channel 0` 是**客户端**主动请求了 X11 转发、被服务端拒绝产生的良性提示，与服务端无关。Dropbear 2020.81 **不支持 X11 转发，也无私有的启用/禁用开关**（其 `--help` 无任何 X11 选项），因此**无法也无法需要在服务端消除该提示**。早期曾误给 dropbear 加 `-x` 开关，结果该版本根本不认 `-x`，导致 dropbear 启动即退出、陷入重启死循环——已在 v1.2.4 修正回归。若想消除该提示，请在**客户端**用 `ssh -o ForwardX11=no` 连接，或关闭 ssh_config 里的 `ForwardX11`、连接时不要带 `-X`/`-Y`。
- **加固命令执行**：修正 `ProotExecutor` 把整条命令误写进子进程 stdin 的脏代码；设置页密码增加字符集校验，启动命令对密码做单引号转义，杜绝特殊字符破坏参数/注入。
- **修正 Manifest**：移除 `.MainActivity` 的 `HOME` 分类，避免被注册为桌面/主屏候选应用。
- **文档与版本控制**：补充 Proot 模式说明；将 proot 脚本纳入 git 跟踪。

### v1.2.3
- **新增命令控制台。** 主界面现在内置一个实时命令终端卡片，可直接在电视上滚动显示 shell 输出，例如 `cat /etc/os-release`、`df -h`，不用再去开 SSH 客户端。
- **Bug 修复与稳定性改进。** 优化了 Shell 执行器与启动流程中的边界情况。

### v1.2.2
- **三层服务自启动。** `run_ubuntu.sh` 现在每次开机按序执行：`/etc/rc.local`（你自定义的命令，保留以兼容旧用法）→ 若已安装则启动 `supervisord` → 通用 `/etc/init.d/*` 遍历，自动发现并启动任何带 init 脚本的服务。
  新装的服务重启后自动开机启动，零配置；危险的系统脚本（halt/reboot/mount/…）会自动跳过，确保安全。

### v1.2.1
- **修复真实环境架构识别。** `rootfsArch()` 现在以 `uname -m` 为基准，仅当真实架构是 x86_64 但 `uname` 被伪装（如 MuMu 模拟器把自己伪装成 `aarch64`）时才纠正为 `amd64`。
  真实的 arm64 / armhf / x86 设备保留其真实架构，chroot 内的 Python/pip 能读到正确值。

---

## 📜 许可证

[MIT](LICENSE) © 2026 吉大大侠 / jiyanlin7113-rgb。可免费用于个人与商业用途。
注意：运行 chroot 需要 Root 权限，风险自负——可能导致保修失效或设备变砖。

---

## ☕ 喜欢就点个 Star

如果 TVUbuntu 让你那台旧电视盒子重获新生，欢迎 **⭐ 点个 Star** 并分享给更多人！
非常期待你的 Pull Request 和 Issue 反馈。🐧
