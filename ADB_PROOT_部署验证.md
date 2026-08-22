# TVUbuntu Proot-ADB 模式 · 部署与验证指南

> 目的：在「untrusted_app 域被 SELinux 拒绝执行 app_data_file」的收紧固件上，
> 让 proot 仍然能跑起来——方法是 App 经 adb 协议，请设备自带的 adbd（system 域）
> 起一个 **shell 域**子进程来 exec proot，从而绕开 App 自身域的执行限制。
> 纯 APK、不需 root、不需改固件运行时（只需开发者选项开启网络 ADB，并把 App 的 adb 公钥授权）。

---

## 一、原理（为什么这样能绕开）

| 路径 | SELinux 域 | 目标文件 type | 结果 |
|---|---|---|---|
| App 直 exec guest bash（原 proot 模式） | `untrusted_app` | `app_data_file` | ❌ EACCES（你的盒子实测） |
| **App → adb → adbd 起 shell 跑 proot（本方案）** | `shell`（adbd 子进程） | `shell_data_file`（`/data/local/tmp`） | ✅ exec 成功 |

关键点：proot 二进制与 rootfs 都落在 `/data/local/tmp/ubuntu/`（shell 域可写可执行），
由 adbd 派生的 shell 进程执行，不受 `untrusted_app` 的限制。

---

## 二、首连一次性准备（每台设备一次）

### 1. 开启网络 ADB 调试
设备「开发者选项」：
- 开启 **USB 调试**
- 开启 **网络 ADB 调试**（或「USB 调试（安全设置）」），默认端口 **5555**

> 部分全志/厂商固件屏蔽了网络 ADB。若第 3 步 PC 连不上，说明固件关了，需要固件放开
> （你掌控全志固件，可在编译期开启 `persist.adb.tcp.port=5555` 或对应 overlay）。

### 2. 授权 App 的 adb 公钥（二选一）

**方式 A：屏上点确认（有显示的设备）**
- 直接点 App 内「启动」（自动走 proot 直 exec 失败后，会尝试 adb 路径；首次连接会在设备上
  弹出「允许 USB 调试？」，点「允许并一律允许」即可）。
- App 的 RSA 公钥由 AndroidKeyStore 自动生成（别名 `tvubuntu_adb_key`），无需导出。

**方式 B：烘焙进固件（无显示/headless 盒子，推荐量产）**
- 在已授权的 PC 上跑一次 App 拿到公钥，或调 `AdbProotService.exportPubkey(context)` 取得
  OpenSSH `ssh-rsa AAAA... tvubuntu` 文本，写入固件镜像的 `/data/misc/adb/adb_keys`（一行一个，标准 ssh-rsa 格式）。
- 这样盒子出厂即信任该 App，无需人工点确认。

> 取公钥文本：在 App 内加一处在 `ensureKey` 后调用 `exportPubkey(context)` 并打印/存文件；
> 或临时在 `AdbProotService.startUbuntu` 开头 `Log.d("adb_pubkey", exportPubkey(context))` 抓 logcat。

### 3. 用 PC 做「手动可行性验证」（强烈建议，先于真机集成验证）
在 PC 上用普通 adb（证明盒子本身支持该路径）：
```bash
adb connect <盒子IP>:5555
adb tcpip 5555
adb push ubuntu-base-22.04.4-base-arm64.tar.gz /data/local/tmp/ubuntu/
adb shell
  mkdir -p /data/local/tmp/ubuntu && cd /data/local/tmp/ubuntu
  gunzip -c ubuntu-base-22.04.4-base-arm64.tar.gz | tar -xf -
  # 把 libproot.so push 进来并 chmod 755
  ./libproot.so -r rootfs -0 -w /root -b /dev -b /proc -b /sys /bin/bash -c 'echo proot-ok; id'
```
若 `proot-ok` + `uid=0(root)` 出现，说明**该盒子的 shell 域能跑 proot**，
App 的 adb 路径必然可行；若仍 `Permission denied`，则盒子的 shell 域也被收紧，需走固件 sepolicy。

---

## 三、App 内行为

- 模式仍为 **ROOT / PROOT / AUTO**（设计不变）。
- PROOT（或 AUTO 无 root）下，若 proot 直 exec 失败且日志命中
  `execve ... Permission denied`（SELinux 拒绝特征），**自动回退 adb 路径**并写标记
  `.adb_launched`，后续「状态/停止/终端/复制日志」都按 adb 路径路由。
- adb 连接参数：默认 `127.0.0.1:5555`，可由 `UbuntuService.getAdbHost/Port`（SharedPreferences
  键 `adb_host`/`adb_port`）覆盖。
- 终端（命令控制台）：adb 模式下每次命令新建一条 proot（`adb shell` 包 proot），与直 exec 模式体验一致。
- SSH 信息显示：guest sshd 在设备本机；**外部 PC** 用 `adb forward tcp:8022 tcp:22` 后连
  `localhost:8022`（用户名/密码同配置）。App 自身终端走 adb shell，不经 SSH。

---

## 四、构建与验证步骤（你来执行）

1. Android Studio 打开 `TVUbuntu`，Sync 后 **Build APK**（纯 Kotlin，无 NDK 改动）。
2. 真机/盒子上开启网络 ADB（见二.1），安装并打开 App。
3. 配置选 **PROOT** 或 **AUTO**，点「启动」：
   - 若直 exec 被 SELinux 拒 → 自动转 adb 路径 → 推送 proot+rootfs → 经 adb 起 proot。
   - 首次会请求 adb 授权（见二.2）；无显示盒子需提前烘焙公钥。
4. 看主页状态：出现「Ubuntu 启动成功（adb 路径）」即成功。
5. 点「复制日志」可拿到 `/data/local/tmp/ubuntu/ubuntu.log` 全文排查。
6. 终端框按 OK 进入，输入 `id` / `uname -a` 验证在 Ubuntu 内。

---

## 五、已知边角与风险

| 项 | 说明 |
|---|---|
| 设备需开网络 ADB | 部分固件屏蔽，需在固件层放开（见二.1） |
| 首连授权 | 无显示盒子必须烘焙公钥（方式 B），否则卡在授权弹窗 |
| adb 端口被 PC 占用 | 若盒子同时连 PC adb，端口冲突；默认 5555，可改 `adb_port` 偏好 |
| App 退出即 proot 退出 | proot 生命周期绑定到 adb 长连接；关闭 App/断连 → proot 随之退出（与原 proot 直 exec 行为一致） |
| API 要求 | adb RSA 密钥用 AndroidKeyStore 签名，需 **API 23+**（盒子普遍满足） |
| rootfs 推送耗时 | 约 28MB 经 adb 流推送，首次约数十秒~1 分钟，进度会显示 |
| 本实现未实机编译验证 | Kotlin adb 客户端为纯逻辑实现，请在真机按四步验证；若握手/推送异常，日志会打印具体 adb 错误消息 |

---

## 六、关键新增/修改文件

- `app/src/main/java/com/ubuntucontroller/AdbClient.kt`（新增，纯 Kotlin adb 客户端：CNXN/AUTH 握手、RSA 由 AndroidKeyStore 签名、shell 执行、带流控的文件推送、持久 shell）
- `app/src/main/java/com/ubuntucontroller/AdbProotService.kt`（新增，adb 路径编排：密钥、推送、启动、状态、停止、命令、日志）
- `app/src/main/assets/start_proot_adb.sh`（新增，在 adb shell 域内解压/修权限/装 SSH/前台常驻 proot）
- `app/src/main/java/com/ubuntucontroller/ProotUbuntuService.kt`（改：直 exec 失败自动回退 adb；detectStatus/stop/runInUbuntu/readLog 按 `.adb_launched` 路由）
- `app/src/main/java/com/ubuntucontroller/UbuntuService.kt`（改：新增 adb host/port 偏好）
