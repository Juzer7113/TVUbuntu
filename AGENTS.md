## 项目概述

Ubuntu 控制器 - 一款面向各类 TV 机顶盒与安卓设备的 Android APK，用于启动/停止 Ubuntu 22.04/24.04/26.04 服务版（最小 base rootfs）并显示 SSH 连接信息。采用 **chroot 方案**（需 root 权限），同时兼容 arm64 真机与 x86_64 模拟器，对各型号机顶盒与安卓设备均有良好兼容性。

## 技术栈

- **语言**: Kotlin
- **构建工具**: Gradle 8.5 + AGP 8.2.2
- **最低 SDK**: 21 (Android 5.0)
- **目标 SDK**: 34 (Android 14)
- **UI**: Material Components + 自定义 TV 适配布局
- **架构**: ViewBinding + Coroutines

## 目录结构

```
app/src/main/
├── AndroidManifest.xml          # 应用清单（TV Leanback 支持）
├── java/com/ubuntucontroller/
│   ├── MainActivity.kt          # 主界面：状态显示、启停控制、SSH 信息
│   ├── SettingsActivity.kt      # 配置界面：脚本路径、SSH 参数
│   ├── UbuntuService.kt         # Ubuntu 服务控制核心逻辑
│   └── ShellExecutor.kt         # Root Shell 命令执行器
└── res/
    ├── layout/
    │   ├── activity_main.xml    # 主界面布局（TV 大按钮 + D-Pad）
    │   └── activity_settings.xml
    ├── drawable/                # 矢量图标、按钮样式、卡片背景
    └── values/                  # 字符串、颜色、主题
```

## 关键入口 / 核心模块

- **ShellExecutor**: 封装 `su` root 命令执行，支持 stdout/stderr/exitCode 返回
- **UbuntuService**: 启停 Ubuntu（通过 shell 脚本）、检测运行状态、获取 SSH 信息（IP/端口/用户名）
- **MainActivity**: TV 适配 UI，状态指示、启停按钮、SSH 信息卡片
- **SettingsActivity**: 可配置启动/停止脚本路径、SSH 端口和用户名

## 运行与预览

- 本项目为 Android APK，不可在浏览器预览（`preview_enable = disabled`）
- 构建命令：`bash scripts/build-apk.sh`（需本地安装 Android SDK）
- 安装方式：`adb install app/build/outputs/apk/release/app-release.apk`

## 用户偏好与长期约束

- 目标设备：各类 ARM64 机顶盒/安卓设备（含 2GB 内存等低配机型）、Android TV；同时兼容 x86_64 模拟器。已对多种机顶盒与安卓设备验证，兼容性良好
- 需要 Root 权限执行 shell 命令控制 Ubuntu chroot
- UI 必须适配 TV 遥控器（D-Pad 导航、大按钮、高对比度）
- 多架构/多版本支持（UbuntuService.rootfsArch()，1.2.1 起按「关键判断」修正）：
  - 以 uname -m 为基准，**仅当真实架构是 x86_64 且 uname 不是 x86_64 时才纠正成 x86_64**
  - MuMu 等 x86 模拟器伪装成 aarch64 → 触发纠正，rootfs 用 amd64 ✅
  - 真实 arm64 盒子（uname=aarch64）/ armhf 老设备（uname=armv7l）/ 真实 x86（uname=x86_64）→ 不触发，保留真值 ✅
  - arm64 真机：ubuntu-base arm64 rootfs → `rootfs.<version>.arm64`
  - x86_64 模拟器：ubuntu-base amd64 rootfs → `rootfs.<version>.amd64`
  - Ubuntu 版本可选 22.04 (jammy) / 24.04 (noble) / 26.04 (resolute)，rootfs 目录按「版本.架构」隔离
- chroot 方案：start.sh 挂载 proc/sys/dev 到 rootfs，并 bind 宿主日志目录到 rootfs/host，chroot 进入后跑 apt 安装 openssh-server 与启动 sshd
- release 使用 debug 签名（便于直接 adb install，不上架商店）
- 默认 Ubuntu 脚本路径：`/data/local/ubuntu/start.sh` 和 `stop.sh`

## 常见问题和预防

- Root 未授权时应用会显示 "Root 未授权" 状态，所有操作需要 su 权限
- ShellExecutor 自动探测 su 模式：交互式 su 会话优先，失败回退 `su -c`（兼容 Magisk/SuperSU/AOSP su/模拟器）
- SSH 信息依赖设备网络接口（优先 WiFi，fallback 到 eth0）
- 如果 Ubuntu 启动脚本不存在，会返回明确的错误提示
- 停止 Ubuntu 时如果 stop.sh 不存在，会执行 fallback 强制停止（pkill + umount）
- 本机构建依赖：Android SDK (`%LOCALAPPDATA%\Android\Sdk`) + JAVA_HOME 指向 Android Studio JBR (JDK 17+)
