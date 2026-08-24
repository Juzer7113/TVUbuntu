# TVUbuntu 开发说明 · 技术路线

> 面向开发者 / 维护者：一页看懂 TVUbuntu 的架构、启动链路与关键实现。
> 本文由 `ADB_PROOT_部署验证.md` 与 `AGENTS.md` 合并而成，并更新至 v1.5.3 当前代码状态。

---

## 1. 项目定位

一款面向各类 TV 机顶盒与安卓设备的 Kotlin Android TV 应用：把盒子变成常驻的 **Ubuntu 服务器**（22.04 / 24.04 / 26.04，最小 base rootfs），自动装 SSH（Root 模式 OpenSSH / Proot 模式 Dropbear），大屏显示连接信息，内置命令控制台。兼容 arm64 真机、x86_64 模拟器（MuMu 等）、armhf 老设备。

## 2. 技术栈

| 项 | 选型 |
|---|---|
| 语言 / 构建 | Kotlin / Gradle 8.5 + AGP 8.2.2 |
| SDK | minSdk 21 (Android 5.0) / targetSdk 34 |
| UI | Material Components + TV 适配（ViewBinding、D-Pad 焦点） |
| 并发 | Coroutines |
| ADB 库 | `libadb-android` 3.1.1（muntashirakon，经典 TCP + 无线 TLS 双通道共用） |

## 3. 技术路线总览：三级回退链（核心）

启动按固定优先级选择路径，**顺序不可颠倒、不可互抢**：

| 级别 | 条件 | 路径 | 说明 |
|---|---|---|---|
| **① Root** | `su` 可用 | `su -c` 直拉 chroot/proot | 最优先，无需 ADB |
| **② ADB** | 无 root 但 ADB 可达 | App → adbd → **shell 域**子进程 exec proot | 大多数无 root 设备的唯一可行方案 |
| **③ 纯 Proot** | 既无 root 又无 ADB | App 自身域直 exec proot | 收紧固件上大概率 EACCES，纯兜底 |

> **关键认知**：② 不是「没 root 才用的提权通道」，而是**绕开 SELinux 域限制**的必要机制——
> 即使盒子有 root，App 自身仍落在 `untrusted_app` 域，照样 exec 不了 `app_data_file`；ADB 让执行挪到 adbd 的 `shell` 域。
> 因此**绝不能因为"设备有 root"就删 ADB**——大量无 root 设备全靠它。

## 4. ADB 路径原理（为什么必须 ADB）

| 路径 | SELinux 域 | 目标文件 type | 结果 |
|---|---|---|---|
| App 直 exec guest bash | `untrusted_app` | `app_data_file` | ❌ EACCES（收紧固件实测） |
| **App → adb → adbd 起 shell 跑 proot** | `shell`（adbd 子进程） | `shell_data_file`（`/data/local/tmp`） | ✅ exec 成功 |

- proot 二进制与 rootfs 都落在 `/data/local/tmp/ubuntu/`（shell 域可写可执行）；
- 纯 APK、不需 root、不需改固件运行时（只需开发者选项开网络 ADB + 首连授权）。

## 5. ADB 路径启动链路（② 的完整流程）

```
1. 获取 ADB 授权
   - 自动获取（开软件/开机）：Android 11+ 无线 TLS 配对（免弹窗）优先，经典 5555 兜底
   - 经典通道「招数梯子」：直连 → autoConnect → 配对码异常 → root 切端口(ctl.restart adbd) → 再直连
2. 部署（libadb sync 协议推送，官方 adb push 通道）
   - libproot.so (0755) → rootfs tar (0644) → 3 个启动脚本 (0755)
3. 权限/SELinux 标签自愈
   - chmod 755 + cp/mv 重建文件（标签变 shell_data_file）+ ls -lZ 诊断进日志
4. 设备侧脚本 start_proot_adb.sh（shell 域）
   - 解压 rootfs → 修复 ELF 执行位/ld 软链 → 补 rootfs 关键目录(tmp/var/tmp/run/dev/proc/sys)
   - PROOT_TMP_DIR=<宿主路径> → install_ssh_proot.sh 装 dropbear → run_ubuntu_proot.sh 常驻
5. 常驻 + 就绪标记
   - proot 生命周期绑定 adb 长连接（App 退出即退出）
   - dropbear 起来 → echo 1 > .ssh_up（宿主标记）→ App 探测到 → 进度 100%
```

## 6. 关键模块与文件

| 文件 | 职责 |
|---|---|
| `AdbTransport.kt` | ADB 传输统一接口（connect/push/exec/startPersistent/close/cancel/isConnected） |
| `AdbClassicTransport.kt` | 经典 TCP 5555 实现（libadb，含授权招数梯子） |
| `AdbWirelessPairing.kt` | 无线调试编排：mDNS 发现（含回环→Wi-Fi IP 修正）+ 配对码 pair + TLS 自发现连接 |
| `AdbWirelessTransport.kt` | 无线 TLS 传输实现（libadb，复用配对阶段建立的 AdbConnection） |
| `AdbSyncPush.kt` | **sync 协议推送**（SEND/DATA/DONE/OKAY，官方 adb push 通道） |
| `AdbProotService.kt` | ADB 路径编排：授权、部署、启动、状态探测、命令、日志 |
| `AdbAutoAcquire.kt` | 开软件/开机自动获取 ADB（无线优先、经典兜底、持久化标记） |
| `AdbNetworkEnabler.kt` | 网络 ADB 自愈/开关（setprop + ctl.restart adbd，按配置端口） |
| `UbuntuAdbManager.kt` / `AdbKeyStore.kt` | libadb 连接管理器 / ADB RSA 密钥 |
| `UbuntuApp.kt` | Application：统一初始化 RuntimeLog |
| `RuntimeLog.kt` | 应用内运行时日志（内存环形 + 落盘轮转），部署每步/ADB 事件全记录 |
| `assets/start_proot_adb.sh` | 设备侧（shell 域）解压/修复/装 SSH/常驻 proot |
| `assets/run_ubuntu_proot.sh` | 容器内：dropbear 自检/重试/看门狗 + .ssh_up 标记 |
| `assets/start_proot.sh` | 纯 proot 模式（③ 兜底）启动脚本 |
| `assets/start.sh` / `stop.sh` | Root 模式（①）chroot 启停 |

## 7. 关键实现要点（踩过的坑，勿再犯）

1. **推送必须走 ADB sync 协议**，绝不用 `shell:cat` 流传二进制：PTY 终端规则会吞 0x04/0x11/0x13；且部分 adbd 上 CLSE 不让 cat stdin 收 EOF → `ins.read()` 永久阻塞（连续两次卡死「推送 proot 二进制」的根因）。
2. **`PROOT_TMP_DIR` 必须是宿主绝对路径**（如 `/data/local/tmp/ubuntu/tmp`），不是 guest 的 `/tmp`——proot 按宿主路径解析，Android 宿主无 /tmp → `can't canonicalize /tmp` 启动即崩。
3. **rootfs 关键目录要补齐**（tmp/var/tmp/run/run/sshd/run/lock/dev/proc/sys/etc/apt…），docker 风格精简 tar 全缺。
4. **权限/SELinux 标签自愈**：sync 推送的文件可能权限位/标签不对，`sh script` 报 Permission denied——`chmod 755` + `cp/mv` 重建（标签变 shell_data_file）。
5. **SSH 就绪标记 `.ssh_up` 用内容区分**：预建 `0`，dropbear 起来 `echo 1`，探测 `grep -q '^1$'`——不能只 `touch`（文件存在即误报就绪）。
6. **探测用 `pgrep -x`（进程名精确匹配）**，绝不用 `pgrep -f libproot.so`——会匹配到执行探测命令的 shell 自身（命令行含该串）。
7. **探测复用已建立的 transport**（同连接开新流），不每次新建 5555 连接；探测超时 20s，避免 adbd 忙时拖死轮询。
8. **进度条 100% = SSH 完全就绪**；未就绪永不提前满；首次安装超时 5 分钟（解压+apt 装 dropbear 可能超 3 分钟）。
9. **设备侧 stdout 接入运行时日志**（`[proot-out]`），ubuntu.log 读不到时也能定位脚本是否跑起来。
10. **脚本必须 LF 行尾**（Windows CRLF 会让 mksh 报 `unknown option`），`.gitattributes` 已强制 `*.sh text eol=lf`。
11. **无线配对/连接必须把 mDNS 解析出的回环地址替换为本机 Wi-Fi IP**：本机 App 经 NsdManager 发现自己广播的 `_adb-tls-pairing`/`_adb-tls-connect` 时，Android 常把服务解析成 `127.0.0.1`；而 adbd 的 TLS 配对/连接服务器只监听在 Wi-Fi 网卡 IP（**不在回环**）→ 直接拿 127.0.0.1 配对必 `ECONNREFUSED`（实测 `failed to connect to /127.0.0.1(port 35125)`）。`AdbWirelessPairing.resolveHost()` 对回环/任意地址统一替换为本机非回环 IPv4（远程设备地址原样保留）；`connectTls()` 也不再用库内 connectTls() 的 mDNS（同样会解析回环），改为自发现后 `mgr.connect(host, port)`——libadb 对 `_adb-tls-connect` 端口的连接由 daemon 发 `A_STLS` 自动升级 TLS，无需显式 TLS 入口。
12. **无障碍自动点击必须有冷却节流**：`performAction(ACTION_CLICK)` 会让按钮 pressed/焦点变化并触发 `TYPE_WINDOW_CONTENT_CHANGED`，若每次事件都去点，会形成「点击→事件→再点击」的自触发死循环（真机实测：循环点 ADB 授权框、刷爆 UI 线程、抢走遥控器焦点、人工无法操作、也点不到最终确认）。`AdbAuthAutoClickService` 点击成功后 4s 冷却期内忽略一切窗口事件；二次确认框（「始终允许」）由冷却后的新 `WINDOW_STATE_CHANGED` 正常触发。
13. **26.04 的 apt 代号是 `questing`，不是 focal**：`start_proot_adb.sh` 曾把 26.04 映射成 focal（20.04 代号）→ apt 源 404 → 装不上 dropbear → SSH 永不就绪。22.04=jammy / 24.04=noble / 26.04=questing。cdimage ubuntu-base 文件名：22/24 带 point（`ubuntu-base-24.04.4-base-armhf.tar.gz`），26.04 不带 point（`ubuntu-base-26.04-base-armhf.tar.gz`）——当前 `rootfsUrl()` 已对齐。
14. **arm 架构 apt 源必须走 `ubuntu-ports`，x86 走 `ubuntu`**：run 阶段曾用默认 x86 源给 armhf 补装 dropbear → 包 404 → SSH 起不来。`start_proot_adb.sh` 现按架构导出 `APT_BASE`；install/run 均三源回退（tuna→aliyun→official，按架构选 ports/ubuntu）。
15. **install 阶段必须先查「已装」再跑 apt**：rootfs 已含 dropbear/sshd（含纯 proot 路径装过的）时跳过 apt，否则慢网络下每次启动都重复 `apt update` 卡死（「启动超时」直接诱因）；apt 只装 SSH 最小集（openssh-server/dropbear/sudo/ca-certificates/iproute2/net-tools/sysvinit-utils），不加 vim/git 等大包。
16. **启动超时不得误报**：5 分钟 deadline 到时若 proot 仍在运行（SSH 未就绪）→ 保持「启动中」（apt 可能因网络慢仍在进行），只有 proot 也没跑才报 ERROR。
17. **开机自启必须走前台服务**：Android 10+ 禁止广播直接 startActivity/长后台工作；BOOT_COMPLETED 经 `AdbBootReceiver` → `startForegroundService(BootService)`（广播豁免）。targetSdk 34 下 `startForeground` 必须带类型（Android 14+ 两参形式抛 MissingForegroundServiceTypeException）；MainActivity 带 HOME category 才是桌面 Launcher（HOME/LEANBACK_LAUNCHER 分两个 intent-filter）。
18. **apt 慢网络必须快速失败换源**：单请求超时 12s、Retries 1，三源回退（tuna→aliyun→official，arm 走 ubuntu-ports）；此前 30s×Retries2×3 源最坏超 5 分钟，正是「启动超时」根因。install/run 脚本的 `mkdir -p` 一律加 `2>/dev/null`（目录已存在时 File exists 是正常噪音，不吞会让用户误判为报错）。
19. **启动 ADB 复用不依赖「ADB 自动获取」开关**：开关只控制开 App/开机时的自动获取行为；启动本身总是先复用已获取的 ADB。收紧固件上纯 proot（untrusted_app exec app_data_file）被 SELinux 拒（EACCES rc=126，start_proot.sh 有完整诊断与固件修复路径），无 root 设备必须走 ADB 通道。

## 8. 构建与验证

```bash
# Windows 本机构建（用户惯例：默认不代为编译 APK）
cd C:\Users\jiyan\Desktop\TVUbuntu
gradlew clean && gradlew assembleDebug   # 改脚本/资源后务必 clean（陈旧缓存）
```

- 验证矩阵：root 盒子（①）/ 无 root 盒子（②）/ 无 root 无 ADB（③ 兜底）；MuMu 模拟器（amd64）。
- 部署验证：开启网络 ADB → 启动 → 进度条走完 100% → 主页「Ubuntu 运行中」+ 真实 IP:端口 → `ssh root@<IP> -p 8022`（Proot）/ `-p 22`（Root）。
- 排障：主页「复制日志」= 应用内运行时日志 + 设备 ubuntu.log 合并导出。

## 9. 常见问题与预防

| 问题 | 预防/解决 |
|---|---|
| `can't canonicalize /tmp` | PROOT_TMP_DIR 宿主绝对路径 + rootfs 目录补齐（见 §7.2/7.3） |
| `sh xxx.sh: Permission denied` | 推送后权限自愈（chmod + cp/mv，见 §7.4） |
| 卡「推送 proot 二进制」 | 确认走 sync 协议（`push(sync)` 日志），勿回 shell:cat（§7.1） |
| 一直「启动中」/进度条不满 | 首次安装 5 分钟内属正常；超时后复制日志看 `[proot-out]`/ubuntu.log |
| SSH 就绪误报 | `.ssh_up` 内容必须为 1（§7.5） |
| 设备 ubuntu.log 读不到 | adb 忙时 readLog 重试一次，仍失败给明确提示；`[proot-out]` 是主要定位源 |
| 脚本执行报 `unknown option` | 行尾必须是 LF（§7.10） |
| 配对码授权报 `ECONNREFUSED 127.0.0.1:xxxxx` | mDNS 把本机服务解析成回环地址，配对/连接前必须经 resolveHost 替换为本机 Wi-Fi IP（§7.11） |

## 10. 用户偏好与长期约束

- 目标设备：ARM64 机顶盒（含 2GB 低配）/ Android TV / x86_64 模拟器；UI 必须适配遥控器（D-Pad、大按钮、高对比度）。
- 架构识别：`uname -m` 为基准，仅当真实架构 x86_64 且 uname 被伪装（MuMu）才纠正 amd64；真 arm64/armhf/x86 保留真值。
- Root 模式 OpenSSH（端口 22）；Proot 模式 Dropbear（端口 8022，≥1024 免特权）。
- 本机构建依赖：Android SDK（`%LOCALAPPDATA%\Android\Sdk`）+ JAVA_HOME 指向 Android Studio JBR（JDK 17+）。
- release 用 debug 签名（便于 adb install，不上架）。
