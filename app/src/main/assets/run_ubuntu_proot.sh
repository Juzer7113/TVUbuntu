#!/bin/bash
# TVUbuntu Proot 模式容器内部运行脚本（静态资产版）
# 由宿主 start_proot.sh 通过 cp -f 下发到 rootfs，再由 proot 以 /bin/bash 执行。
# 定制项全部通过环境变量传入（由 start_proot.sh 在 proot 调用前 export）：
#   PORT      SSH 端口（默认 8022）
#   SSH_PASS  root 密码
#   CODENAME  Ubuntu 代号（jammy/noble/...）
#   MARCH     机器架构（x86_64/aarch64/armhf）
#   APT_BASE  apt 镜像源基址
# 重要：本 proot 构建的 path.c 对 OpenSSH 8.x 特权分离子进程发出的
#   openat(AT_FDCWD, "", AT_EMPTY_PATH) 空路径断言失败（compare_paths2: length1 > 0），
#   导致 proot 以 SIGABRT 崩溃、SSH 连接被 reset。
# 因此 proot 模式改用 Dropbear 作为 SSH 服务（对 proot 友好，无该缺陷）；
# OpenSSH 仍保留以复用其 sftp-server 二进制与客户端工具。Root 模式（chroot）不变，仍用 OpenSSH。
# 历史教训：切勿改回"运行时 heredoc 生成 + sed 替换"方案——App 派生的 Android
# 系统 shell 在无可写 TMPDIR 时 heredoc 会产出 0 字节空文件，容器脚本从未执行。
set +e
umask 022
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

# B1 修复：proot -0 会把宿主 App 的补充组 gid（如 3003 inet / 9997 everybody / 厂商私有组）
# 透传到容器，而 rootfs 的 /etc/group 没有这些条目，登录时反复报
# "groups: cannot find name for group ID XXXXX"。动态补齐缺失 gid，让 groups/id 正常解析
# （纯显示修复，不影响权限）。
if [ -f /etc/group ]; then
  for g in $(id -G 2>/dev/null); do
    case "$g" in
      ''|*[!0-9]*) continue ;;
    esac
    grep -qE ":$g:" /etc/group 2>/dev/null || echo "gid$g:x:$g:" >> /etc/group
  done
fi

# 环境变量兜底默认值（正常情况由宿主 export 传入）
PORT="${PORT:-8022}"
CODENAME="${CODENAME:-jammy}"
# APT_BASE 由宿主 start_proot_adb.sh 按架构导出（arm 走 ubuntu-ports，否则 apt 404）；
# 此处仅兜底：未传入时按 MARCH 选择 tuna 源。
if [ -z "$APT_BASE" ]; then
  case "$MARCH" in
    x86_64) APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu" ;;
    *)      APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" ;;
  esac
fi

# 探针：在重定向之前先打一行到 /hostlog（已 bind 到宿主 ubuntu.log），
# 用于确认 proot 是否真正进入 bash；即便后续重定向失败，这一行也已在主日志中。
echo "[proot-inner] bash 已进入 pid=$$ ppid=$PPID at $(date)" >> /hostlog 2>/dev/null || true
# 内联日志直接写入 /hostlog（= 宿主 ubuntu.log），保证踪迹进入主日志，无需依赖独立文件。
exec >> /hostlog 2>&1
echo "===== run_ubuntu_proot.sh 启动 $(date) ====="
echo "[proot-inner] PORT=$PORT CODENAME=$CODENAME MARCH=$MARCH"
echo "[proot-inner] PATH=$PATH"
echo "[proot-inner] dropbear bin: $(command -v dropbear || ls -l /usr/sbin/dropbear 2>&1)"
echo "[proot-inner] dropbear 版本: $(dropbear -h 2>&1 | head -1)"

mkdir -p /etc/dropbear /run/sshd
chmod 700 /etc/dropbear 2>/dev/null

# 若 dropbear 缺失，补装（proot 下 openssh-server 半配置不影响 dropbear）。
# 三源回退（与 install_ssh_proot.sh 一致）：tuna → aliyun → official，均按架构选 ports/ubuntu。
if ! command -v dropbear >/dev/null 2>&1; then
  echo "[proot-inner] 未找到 dropbear，尝试 apt 安装（fallback）..."
  export DEBIAN_FRONTEND=noninteractive
  export DEBCONF_NONINTERACTIVE_SEEN=true
  case "$MARCH" in
    x86_64)
      M_TUNA="http://mirrors.tuna.tsinghua.edu.cn/ubuntu"
      M_ALIYUN="http://mirrors.aliyun.com/ubuntu"
      M_OFFICIAL="http://archive.ubuntu.com/ubuntu"
      ;;
    *)
      M_TUNA="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
      M_ALIYUN="http://mirrors.aliyun.com/ubuntu-ports"
      M_OFFICIAL="http://ports.ubuntu.com/ubuntu-ports"
      ;;
  esac
  APT_OPTS="-o Acquire::ForceIPv4=true -o Acquire::Check-Valid-Until=false -o Acquire::http::Timeout=12 -o Acquire::https::Timeout=12 -o Acquire::Retries=1"
  for MIRROR in "$M_TUNA" "$M_ALIYUN" "$M_OFFICIAL"; do
    cat > /etc/apt/sources.list <<EOF
deb ${MIRROR} ${CODENAME} main restricted universe multiverse
deb ${MIRROR} ${CODENAME}-updates main restricted universe multiverse
deb ${MIRROR} ${CODENAME}-security main restricted universe multiverse
EOF
    rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null
    echo "[proot-inner]   apt 源回退：$MIRROR"
    if apt-get update -y $APT_OPTS >> /hostlog 2>&1; then
      if apt-get install -y --no-install-recommends dropbear $APT_OPTS >> /hostlog 2>&1; then
        echo "[proot-inner]   dropbear 安装成功（源：$MIRROR）"
        break
      else
        echo "[proot-inner]   dropbear 安装失败，换源重试"
      fi
    else
      echo "[proot-inner]   apt update 失败，换源重试"
    fi
  done
fi

# 生成 dropbear 主机密钥（缺失才生成，best-effort）。
# 说明：proot 下 dropbearkey CLI 在部分固件上偶发取熵失败，但 dropbear 自身 -R 选项
# 会在启动时稳定生成缺失密钥（已在当前固件验证监听 8022 成功）。本函数失败不致命，-R 兜底。
ensure_dev_random() {
  for d in random urandom; do
    [ -e "/dev/$d" ] && continue
    [ -e /dev/urandom ] && ln -sf /dev/urandom "/dev/$d" 2>/dev/null
  done
}
gen_dropbear_keys() {
  mkdir -p /etc/dropbear
  chmod 700 /etc/dropbear 2>/dev/null
  ensure_dev_random
  for kt in rsa ecdsa ed25519; do
    f="/etc/dropbear/dropbear_${kt}_host_key"
    [ -f "$f" ] && { echo "[proot-inner] dropbear 主机密钥已存在: $kt"; continue; }
    bits=""; [ "$kt" = "rsa" ] && bits="-s 3072"
    echo "[proot-inner] 生成 dropbear 主机密钥: $kt"
    if dropbearkey -t "$kt" $bits -f "$f" 2>>/hostlog; then
      echo "[proot-inner]   $kt OK ($(wc -c < "$f" 2>/dev/null || echo ?) bytes)"
    else
      echo "[proot-inner]   $kt 失败，将由 dropbear -R 在启动时自动生成"
    fi
  done
}
# 启动前先 best-effort 生成；最终由 dropbear -R 兜底
gen_dropbear_keys

# sftp-server 软链：dropbear 默认找 /usr/lib/sftp-server，Ubuntu 把 sftp-server 装在
# /usr/lib/openssh/sftp-server，做一条软链；dropbear 据此在收到 sftp 子系统请求时调用。
# 注意：dropbear 没有“-s <sftp路径>”这种选项（-s 是“禁用密码登录”且不接受参数），
# 切勿给 dropbear 传 -s <path>，否则启动即报 “Invalid argument: <path>” 崩溃。
if [ -x /usr/lib/openssh/sftp-server ] && [ ! -e /usr/lib/sftp-server ]; then
  ln -sf /usr/lib/openssh/sftp-server /usr/lib/sftp-server 2>/dev/null
fi

# 确保 root 密码可用：SSH_PASS 提供时每次校准，保证 dropbear 登录不被拒
if [ -n "$SSH_PASS" ]; then
  if echo "root:$SSH_PASS" | chpasswd 2>>/hostlog; then
    touch /etc/.ssh_password_initialized 2>/dev/null
    echo "[proot-inner] root 密码已校准（dropbear 登录可用）"
  else
    echo "[proot-inner] WARNING: root 密码设置失败，dropbear 登录可能拒绝"
  fi
fi

# 生产化：服务自启动（与 root 模式保持一致）
if [ -x /etc/rc.local ]; then
  echo "[proot-inner] 执行 /etc/rc.local ..."
  nohup /etc/rc.local >> /hostlog 2>&1 &
fi
if [ -x /usr/bin/supervisord ]; then
  echo "[proot-inner] 启动 supervisord ..."
  /usr/bin/supervisord -c /etc/supervisor/supervisord.conf 2>&1
fi
DANGEROUS_INIT="halt reboot poweroff shutdown killall single sendsigs killprocs umountfs umountroot umountnfs umountiscsi checkroot checkfs mountall mountnfs mountkernfs mountdevsubfs bootlogd bootlogs bootmisc skeleton rc rcS rc.local hwclock procps udev eudev mtab networking network-manager dbus ssh sshd supervisor supervisord dropbear"
echo "[proot-inner] 通用服务自启动（遍历 /etc/init.d）..."
for init in /etc/init.d/*; do
  [ -x "$init" ] || continue
  name="$(basename "$init")"
  case " $DANGEROUS_INIT " in
    *" $name "*) continue ;;
  esac
  case "$name" in
    *.sh) continue ;;
  esac
  "$init" start >> /hostlog 2>&1 || true
done

# ---------- 启动 dropbear：自检 + 重试 + 看门狗（生产化、自愈）----------
# 不向 dropbear 传 -s 参数（-s 是“禁用密码登录”，不接受路径参数）；sftp 子系统由上方
#   /usr/lib/sftp-server 软链到 openssh 的 sftp-server 提供，dropbear 按默认路径自动调用。

start_dropbear() {
  mkdir -p /etc/dropbear /run/sshd
  chmod 700 /etc/dropbear 2>/dev/null
  gen_dropbear_keys
  echo "[proot-inner] 以守护态启动 dropbear 端口 $PORT ..."
  # -E 日志到 stderr（已被 exec 重定向到 /hostlog）；-R 缺失密钥时运行时生成；-p 监听端口
  # 注意 1：dropbear 的 -s 是"禁用密码登录"而非指定 sftp 路径；指定 sftp 请改用默认路径
  #   /usr/lib/sftp-server（已在上方软链到 openssh 的 sftp-server）。误用 -s <path> 会令
  #   dropbear 启动即报 "Invalid argument: <path>" 而崩溃，故此处绝不传 -s。
  # 注意 2：dropbear 2020.81 不支持 X11 转发，也【没有任何】启用/禁用该项的命令行开关
  #   （其 --help 列出的全部选项中不存在 X11 相关标志）。登录时客户端出现的
  #   "X11 forwarding request failed on channel 0" 是【客户端】主动请求了 X11 转发而被
  #   服务端拒绝产生的良性提示，与服务端无关，无法也不能在服务端消除；不要给 dropbear
  #   再尝试加 -x / -X 之类的开关（均属 Invalid option，会令守护进程直接退出）。
  #   若想消除该提示，请在客户端侧处理：用 ssh -o ForwardX11=no 连接，或关闭 ssh_config
  #   里的 ForwardX11，且连接时不要带 -X / -Y 参数。
  dropbear -E -R -p "$PORT" -P /run/dropbear.pid 2>&1
  sleep 2
  if pgrep -x dropbear >/dev/null 2>&1; then
    echo "[proot-inner] dropbear 启动成功 pid=$(pgrep -x dropbear | tr '\n' ' ')"
    echo 1 > /hostsshup 2>/dev/null || true   # 通知宿主：SSH 已就绪（App 探测 .ssh_up == 1）
    return 0
  fi
  echo "[proot-inner] dropbear 未就绪"
  return 1
}

# 诊断：前台 -v 抓取致命错误到 /hostlog
diag_dropbear() {
  echo "[proot-inner] 诊断：前台运行 dropbear (-v 调试) 抓取致命错误（4s）..."
  dropbear -E -R -p "$PORT" -P /run/dropbear.pid -v >> /hostlog 2>&1 &
  local dp=$!
  sleep 4
  kill "$dp" 2>/dev/null
  echo "[proot-inner] 诊断结束。/hostlog 末尾（dropbear 相关）："
  tail -40 /hostlog 2>/dev/null | grep -iE 'dropbear|error|fatal|refused|denied|bind' | tail -20
}

# 清理可能残留的 dropbear 实例，避免端口被占
pkill -x dropbear 2>/dev/null; sleep 1

# 重试拉起 dropbear（最多 10 次，每次间隔 5s）
i=0
while [ $i -lt 10 ]; do
  start_dropbear
  if pgrep -x dropbear >/dev/null 2>&1; then
    echo "[proot-inner] dropbear 已在运行 pid=$(pgrep -x dropbear | tr '\n' ' ')"
    echo 1 > /hostsshup 2>/dev/null || true   # 通知宿主：SSH 已就绪（App 探测 .ssh_up == 1）
    break
  fi
  if [ $i -eq 0 ]; then diag_dropbear; fi
  echo "[proot-inner] dropbear 未就绪，5s 后重试（$((i+1))/10）..."
  sleep 5
  i=$((i+1))
done

# 启动后校验主机密钥已落地（dropbear -R 首次启动会生成），避免"每次重启换密钥"告警
sleep 1
if ls /etc/dropbear/dropbear_*_host_key >/dev/null 2>&1; then
  echo "[proot-inner] dropbear 主机密钥已落地: $(ls -1 /etc/dropbear/dropbear_*_host_key | wc -l) 把"
else
  echo "[proot-inner] WARNING: 启动后仍未发现主机密钥，尝试显式生成兜底..."
  gen_dropbear_keys
fi

# 看门狗：保持容器常驻，并周期探活、自动重启 dropbear（自愈）
echo "[proot-inner] 进入 dropbear 看门狗循环（每 15s 探活）..."
while true; do
  if ! pgrep -x dropbear >/dev/null 2>&1; then
    echo "[proot-inner] 看门狗：dropbear 未运行，尝试重启..."
    start_dropbear
    echo "[proot-inner] 看门狗：重启结果 exit=$? pid=$(pgrep -x dropbear | tr '\n' ' ' || echo none)"
  fi
  sleep 15
done
