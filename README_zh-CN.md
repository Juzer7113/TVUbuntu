# 📺🐧 TVUbuntu —— 在安卓电视盒子上跑一个真正的 Ubuntu

> 把任意一台吃灰的安卓电视盒子或安卓设备，变成一台常驻运行的 **Ubuntu 服务器**。
> 这是一个用 Kotlin 编写的安卓 **TV** 应用：通过原生的 **chroot** 启动真正的 Ubuntu（22.04 / 24.04 / 26.04），
> 自动安装 **OpenSSH**，并直接在大屏幕上显示 SSH 连接信息。

[![平台](https://img.shields.io/badge/平台-Android%20TV-1A1A2E?logo=android&logoColor=white)](https://www.android.com/tv/)
[![语言](https://img.shields.io/badge/语言-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![最低 SDK](https://img.shields.io/badge/最低%20SDK-21%20(Android%205.0)-34A853)](https://developer.android.com/about/versions)
[![许可证](https://img.shields.io/badge/许可证-MIT-green.svg)](LICENSE)
[![APK](https://img.shields.io/badge/APK-TVUbuntu%201.2.3-orange)](https://github.com/jiyanlin7113-rgb/TVUbuntu/releases)

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
| 🐧 **原生 chroot** | 使用内核原生的 `chroot`，无需 `proot` 二进制，更轻量、更快。 |
| 📡 **自动下载 rootfs** | 应用自动下载最小化的 `ubuntu-base` 压缩包，并在设备端完成 gzip 解压（绕开盒子自带 `toybox tar` 的坑）。 |
| 🔑 **零配置 SSH** | 自动安装并启动 `openssh-server`，随后在大屏展示 主机 / 用户 / 密码 / 端口。 |
| 🎮 **TV 遥控器 UI** | 方向键（D-Pad）导航、焦点放大「光标」动画、大号高对比度按钮——专为遥控器而非触屏设计。 |
| 🌐 **多架构支持** | `arm64`（真机盒子）、`amd64`（x86_64 模拟器如 MuMu）、`armhf`（32 位 ARM）。 |
| 🔄 **多版本支持** | Ubuntu **22.04**（jammy）/ **24.04**（noble）/ **26.04**（resolute），按版本+架构隔离。 |
| ⚙️ **开机自启** | 可选「开机自动启动 Ubuntu」+ 开机广播接收器自动拉起。 |
| 🛡️ **稳健的 Shell 层** | `ShellExecutor` 自动探测 `su` 模式（交互式 vs `su -c`），流式实时进度，可承受大输出不卡死。 |
| 🚀 **生产级 Web 栈** | 一条命令的 `bootstrap_server.sh` 在 `supervisor` 下拉起 **nginx + PHP-FPM + MariaDB + Redis**。 |
| 🖥️ **命令控制台** | 主界面新增实时命令终端卡片，可直接在电视上查看 `cat /etc/os-release`、`df -h` 等输出，无需离开电视去开 SSH 客户端。 |

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

## 📂 目录结构

```
TVUbuntu/
├── app/
│   ├── build.gradle.kts                 # App 模块：minSdk 21，版本 1.2.3
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # TV Leanback 启动器 + 开机接收器
│       ├── assets/
│       │   ├── start.sh                 # chroot 引导：挂载、装 SSH、运行
│       │   └── stop.sh                  # 收尾：杀掉 sshd、卸载挂载
│       ├── java/com/ubuntucontroller/
│       │   ├── MainActivity.kt          # TV 主界面：状态、启停、SSH 卡片
│       │   ├── SettingsActivity.kt      # 脚本 / SSH / 版本 / 架构 配置
│       │   ├── UbuntuService.kt         # 核心逻辑：rootfs、架构、状态、SSH 信息
│       │   ├── ShellExecutor.kt         # root shell 执行器（自动探测 su 模式）
│       │   ├── BootReceiver.kt          # 设备开机后自动拉起
│       │   └── TvFocusUtils.kt          # 方向键焦点辅助（TV 光标）
│       └── res/                         # 布局、图标、字符串、颜色、主题
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
