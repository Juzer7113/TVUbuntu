#!/bin/bash
# TVUbuntu Proot 模式 —— 在 proot 内首次安装 openssh-server
# 由 start_proot.sh 复制进 rootfs 并调用
set +e

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export LC_ALL=C
export LANG=C
# proot 下部分包 postinst（如 openssh-server 的 ucf）用 statx 对 /tmp 临时文件做校验，
# 而本 proot 构建未拦截 statx，临时文件路径翻译失败。改用 /var/tmp 作为 TMPDIR 降低踩坑概率。
export TMPDIR=/var/tmp

HOSTLOG="/hostlog"
say() { echo "[proot-install] $1" >> "$HOSTLOG" 2>/dev/null || echo "[proot-install] $1"; }

say "容器 shell 就绪"

# ===== Ubuntu 26.04 uutils coreutils 兼容修复 =====
# 26.04 的 coreutils 换成 Rust 版 multi-call（/usr/bin/coreutils，各工具是 symlink，靠 argv[0]
# 分发子命令）。proot 用自定义 loader 加载程序时把 argv[0] 改写成临时文件（prooted-*），
# uutils 认不出即报 "coreutils: unknown program 'prooted-*'"，mkdir/rm/ls 等全部失效、
# apt 无法工作。修复：解包 host 预下载的 gnu-coreutils（/usr/bin/gnu* 独立 ELF，不依赖
# argv[0]）+ libcap2，并把 /usr/bin 下指向 uutils 的 symlink 全部替换为 gnu* 独立版。
# 注意：此处只能用 bash 内建与独立 ELF（dpkg-deb/tar/gnu*），不能用 mkdir/rm/ln 等 uutils 命令。
if [ -d /pkgs ]; then
  FIXED=0
  for deb in /pkgs/*.deb; do
    [ -e "$deb" ] || continue
    if dpkg-deb -x "$deb" / 2>>"$HOSTLOG"; then
      say "  解包 ${deb##*/} OK"
    else
      say "  解包 ${deb##*/} 失败"
    fi
  done
  TOOLS="arch base64 basename basenc cat chcon chgrp chmod chown chroot cksum comm cp csplit cut date dd df dir dircolors dirname du echo env expand expr factor false flock fmt fold groups head hostid id install join link ln logname ls md5sum mkdir mkfifo mknod mktemp mv nice nl nohup nproc numfmt od paste pathchk pinky pr printenv printf ptx pwd readlink realpath rm rmdir runcon seq shred sleep sort split stat stty sum sync tac tail tee test timeout touch tr true truncate tsort tty uname unexpand uniq unlink users vdir wc who whoami yes"
  for t in $TOOLS; do
    [ -e "/usr/bin/gnu$t" ] || continue
    if [ -L "/usr/bin/$t" ]; then
      /usr/bin/gnurm -f "/usr/bin/$t" 2>>"$HOSTLOG"
      /usr/bin/gnuln -s "gnu$t" "/usr/bin/$t" 2>>"$HOSTLOG"
      FIXED=$((FIXED+1))
    fi
  done
  say "uutils coreutils 修复完成（替换 $FIXED 个 symlink）；mkdir -> $(readlink /usr/bin/mkdir 2>/dev/null || echo '?')"
fi

# 关键目录兜底（目录已存在时 File exists 属正常，吞掉 stderr 避免日志噪音）
mkdir -p /tmp /var/tmp /run/sshd /run/lock /usr/local /etc/ssh /etc/apt /etc/apt/sources.list.d \
         /var/lib/apt/lists/partial /var/cache/apt/archives/partial 2>/dev/null
chmod 1777 /tmp /var/tmp /run/lock 2>/dev/null
chmod 755 /run 2>/dev/null
say "关键路径检查: /tmp=$(test -d /tmp && echo OK || echo MISS) /etc/apt=$(test -d /etc/apt && echo OK || echo MISS) apt-get=$(test -x /usr/bin/apt-get && echo OK || echo MISS)"

# 已安装检测：dropbear/sshd 已存在（含纯 proot 路径装过、或上次安装已成功）→ 跳过 apt。
# 关键：避免慢网络下重复 apt update 拖死启动（本次「启动超时」的根因之一）。
if command -v dropbear >/dev/null 2>&1 || [ -x /usr/sbin/dropbear ] || \
   [ -x /usr/bin/dropbear ] || [ -x /usr/sbin/dropbearmulti ] || [ -x /usr/sbin/sshd ]; then
  say "检测到 SSH 服务已安装（dropbear/sshd 存在），跳过 apt 安装"
  # 密码仍校准一次（幂等，保证 dropbear 登录可用）
  if [ -n "$SSH_PASS" ]; then
    if echo "root:$SSH_PASS" | chpasswd >> "$HOSTLOG" 2>&1; then
      touch /etc/.ssh_password_initialized 2>/dev/null
      say "root 密码已校准"
    fi
  fi
  exit 0
fi

# 根据架构选择 apt 源
# 注意：必须用 start_proot.sh 正确导出的 $MARCH（amd64->x86_64 / arm64->aarch64），
# 不能用 $(uname -m)：proot 容器内 fakeuname 尚未安装时 uname -m 返回的是宿主伪装架构，
# 会导致 amd64 rootfs 误选 ubuntu-ports 仓库而 404。
case "$MARCH" in
  x86_64)
    MIRROR_TUNA="http://mirrors.tuna.tsinghua.edu.cn/ubuntu"
    MIRROR_ALIYUN="http://mirrors.aliyun.com/ubuntu"
    MIRROR_OFFICIAL="http://archive.ubuntu.com/ubuntu"
    ;;
  *)
    MIRROR_TUNA="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
    MIRROR_ALIYUN="http://mirrors.aliyun.com/ubuntu-ports"
    MIRROR_OFFICIAL="http://ports.ubuntu.com/ubuntu-ports"
    ;;
esac

# 慢网络友好：单次请求超时 12s、重试 1 次——快速失败换源，避免在不可达源上干等
# （此前 30s×Retries2×3源×3套件最坏可超 5 分钟，正是「启动超时」的根因）。
APT_OPTS="-o Acquire::ForceIPv4=true -o Acquire::Check-Valid-Until=false -o Acquire::http::Timeout=12 -o Acquire::https::Timeout=12 -o Acquire::ftp::Timeout=12 -o Acquire::Retries=1"

write_sources() {
  local base="$1"
  mkdir -p /etc/apt /etc/apt/sources.list.d 2>/dev/null
  cat > /etc/apt/sources.list <<EOF
deb $base $CODENAME main restricted universe multiverse
deb $base ${CODENAME}-updates main restricted universe multiverse
deb $base ${CODENAME}-security main restricted universe multiverse
EOF
}

say "清理 apt/dpkg 锁与残留源"
rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock* 2>/dev/null
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* 2>/dev/null
rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null
mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /run/sshd 2>/dev/null

say "dpkg --configure -a"
dpkg --configure -a >> "$HOSTLOG" 2>&1

say "apt-get update (tuna)"
write_sources "$MIRROR_TUNA"
if ! apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1; then
  say "tuna 失败，回退 aliyun"
  write_sources "$MIRROR_ALIYUN"
  if ! apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1; then
    say "aliyun 失败，回退 official"
    write_sources "$MIRROR_OFFICIAL"
    apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1
  fi
fi

say "apt-get install openssh-server + 常用工具"

# 26.04 的 openssh-server 依赖 "systemd | systemd-standalone-sysusers | systemd-sysusers"（创建 sshd 用户），
# apt 默认选完整 systemd，其 postinst 会调用 systemd-tmpfiles/sysusers，触发本 proot 的
# path.c:547 compare_paths2 空路径断言（openat AT_EMPTY_PATH），导致 proot SIGABRT 整个容器退出、
# 安装被中断（22.04 无此依赖故正常）。修复：预装最小的 systemd-sysusers，让 apt 的 or 依赖选中它、
# 避免拉入完整 systemd。22.04/24.04 无该包时报错可忽略。
say "预装 systemd-sysusers（避免 apt 拉入完整 systemd 触发 proot 空路径断言崩溃）..."
if apt-get install -y --no-install-recommends systemd-sysusers $APT_OPTS >> "$HOSTLOG" 2>&1; then
  say "  systemd-sysusers 预装 OK（apt 将不再拉入完整 systemd）"
else
  say "  预装失败或当前版本无此包（22.04/24.04 正常，忽略）"
fi

# 注：proot 下 openssh-server 的 postinst 会调用 ucf，ucf 在 /tmp 建临时文件后用 statx 校验，
# 本 proot 构建未拦截 statx，临时文件路径未被翻译而报 "No such file or directory"，postinst 因此非零退出。
# 但软件包文件已解包（/usr/sbin/sshd、ssh-keygen 已存在），主机密钥与 sshd_config 由下方手动补完，
# 故此处容忍安装返回非 0，不致命；脚本末尾还会把 dpkg 半配置状态修复为 installed(ii)，消除中断提示。
# 注意：只装 SSH 必需的最小集（dropbear 供 proot 监听、openssh-server 复用其 sftp-server/客户端）。
# 不要加 vim/htop/git/wget 等大包——慢网络下每多一个包都显著拉长安装时间（「启动超时」的直接诱因），
# 用户可在 runInUbuntu 命令控制台里按需自装。
if ! apt-get install -y --no-install-recommends openssh-server dropbear \
    sudo ca-certificates iproute2 net-tools sysvinit-utils $APT_OPTS >> "$HOSTLOG" 2>&1; then
  say "WARNING: apt-get install 返回非 0（proot 下 openssh-server postinst 常见，继续手动收尾 sshd）"
fi

# fakeuname 修复：x86_64 环境被伪装成 aarch64 时（如 MuMu）
if [ "$MARCH" = "x86_64" ] && [ "$(uname -m)" != "x86_64" ]; then
  say "检测到 uname 架构伪装（$(uname -m) != x86_64），安装 fakeuname 修复"
  apt-get install -y --no-install-recommends gcc libc6-dev $APT_OPTS >> "$HOSTLOG" 2>&1
  cat > /tmp/fakeuname.c <<'CEOF'
#define _GNU_SOURCE
#include <sys/utsname.h>
#include <string.h>
#include <dlfcn.h>
static int (*real_uname)(struct utsname *) = 0;
int uname(struct utsname *buf) {
    if (!real_uname) real_uname = (int (*)(struct utsname *))dlsym(RTLD_NEXT, "uname");
    int ret = real_uname ? real_uname(buf) : -1;
    if (ret == 0 && buf) {
        strncpy(buf->machine, "x86_64", sizeof(buf->machine) - 1);
        buf->machine[sizeof(buf->machine) - 1] = '\0';
    }
    return ret;
}
CEOF
  gcc -shared -fPIC -O2 -o /usr/local/lib/fakeuname.so /tmp/fakeuname.c >> "$HOSTLOG" 2>&1
  rm -f /tmp/fakeuname.c
  if [ -f /usr/local/lib/fakeuname.so ]; then
    smoke="$(LD_PRELOAD=/usr/local/lib/fakeuname.so uname -m 2>/dev/null)"
    if [ "$smoke" = "x86_64" ]; then
      echo "/usr/local/lib/fakeuname.so" > /etc/ld.so.preload
      say "fakeuname 修复完成：uname -m 现返回 $(uname -m)"
    else
      say "fakeuname 冒烟测试未通过（got: $smoke），不全局生效"
      rm -f /usr/local/lib/fakeuname.so
    fi
  else
    say "fakeuname 编译失败，跳过（不影响 SSH）"
  fi
else
  say "uname 架构正常（$(uname -m)），无需 fakeuname"
fi

mkdir -p /run/sshd /etc/ssh
# 手动生成主机密钥：proot 下 openssh-server postinst 可能未执行到这一步
if ! ssh-keygen -A >> "$HOSTLOG" 2>&1; then
  say "WARNING: ssh-keygen -A 返回非 0，尝试逐个密钥类型生成"
  for kt in rsa ecdsa ed25519; do
    [ -f "/etc/ssh/ssh_host_${kt}_key" ] || ssh-keygen -q -N '' -t "$kt" -f "/etc/ssh/ssh_host_${kt}_key" >> "$HOSTLOG" 2>&1
  done
fi
# 确保 sshd_config 存在（postinst 失败时可能缺失）
if [ ! -f /etc/ssh/sshd_config ]; then
  cat > /etc/ssh/sshd_config <<'EOF'
Port 22
ListenAddress 0.0.0.0
PermitRootLogin yes
PasswordAuthentication yes
PubkeyAuthentication yes
UsePAM no
Subsystem sftp internal-sftp
EOF
  say "已写入最小化 sshd_config"
fi
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
# 修正 sshd 目录/密钥文件权限（proot 下权限偶尔异常）
chmod 755 /etc/ssh 2>/dev/null
chmod 600 /etc/ssh/ssh_host_*_key 2>/dev/null
chmod 644 /etc/ssh/ssh_host_*_key.pub 2>/dev/null

# ---- Dropbear：proot 模式实际使用的 SSH 服务 ----
# OpenSSH 8.x 的特权分离子进程会发出 openat(AT_FDCWD,"",AT_EMPTY_PATH) 空路径，
# 触发本 proot 构建 path.c 的 compare_paths2 断言崩溃（SIGABRT），导致 SSH 连接被 reset。
# Dropbear 对该 proot 友好，作为 proot 模式监听服务；OpenSSH 仅保留以复用 sftp-server/客户端。
if command -v dropbear >/dev/null 2>&1; then
  mkdir -p /etc/dropbear
  chmod 700 /etc/dropbear
  for kt in rsa ecdsa ed25519; do
    f="/etc/dropbear/dropbear_${kt}_host_key"
    [ -f "$f" ] && continue
    bits=""; [ "$kt" = "rsa" ] && bits="-s 3072"
    if dropbearkey -t "$kt" $bits -f "$f" >/dev/null 2>&1; then
      say "dropbear 主机密钥: $kt OK"
    else
      say "WARNING: dropbearkey $kt 失败"
    fi
  done
  [ -x /usr/lib/openssh/sftp-server ] && [ ! -e /usr/lib/sftp-server ] && ln -sf /usr/lib/openssh/sftp-server /usr/lib/sftp-server
  say "dropbear 主机密钥已就绪"
else
  say "WARNING: dropbear 未安装，proot 模式 SSH 可能不可用（仅 OpenSSH，存在 proot 崩溃风险）"
fi

# 密码只在首次安装时初始化一次
if [ -n "$SSH_PASS" ] && [ ! -f /etc/.ssh_password_initialized ]; then
  if echo "root:$SSH_PASS" | chpasswd >> "$HOSTLOG" 2>&1; then
    touch /etc/.ssh_password_initialized
    say "root 密码已初始化（仅首次）"
  else
    say "root 密码初始化失败"
  fi
fi

# ---- 修复 openssh-server 在 proot 下的“半配置”状态 ----
# 现象：proot 未拦截 statx，openssh-server postinst 中的 ucf 对 /tmp 临时文件做 statx 校验失败，
# 导致 postinst 非零退出，dpkg 将其标记为 half-configured(iU)。包文件已解包、sshd 已可用，
# 但日后在容器内再跑 apt/dpkg 时会提示 “dpkg was interrupted, you must run dpkg --configure -a”。
# 修复：手动收尾已完成（主机密钥 + sshd_config + 权限），给 postinst 注入守卫使其在已就绪时直接
# exit 0，再 dpkg --configure 一次，让 dpkg 状态落为 installed(ii)，从而消除中断提示。
SSHD_PKG_STATUS="$(dpkg -s openssh-server 2>/dev/null | awk -F': ' '/^Status:/{print $2}')"
if [ -x /usr/sbin/sshd ] && ! echo "$SSHD_PKG_STATUS" | grep -q " installed"; then
  say "检测到 openssh-server 状态=[$SSHD_PKG_STATUS]（非 installed），执行 dpkg 收尾以清除半配置"
  # 兜底：确保主机密钥与配置齐备（上方已生成，这里再确认）
  mkdir -p /run/sshd /etc/ssh
  ssh-keygen -A >> "$HOSTLOG" 2>&1 || true
  # 给 postinst 注入守卫：sshd 二进制与主机密钥已就绪时直接 exit 0，绕过 statx 陷阱
  POSTINST=/var/lib/dpkg/info/openssh-server.postinst
  if [ -f "$POSTINST" ] && ! grep -q "PROOT_HALFCONFIG_GUARD" "$POSTINST"; then
    cp -a "$POSTINST" "${POSTINST}.bak" 2>/dev/null
    {
      head -n 1 "$POSTINST"
      echo '# PROOT_HALFCONFIG_GUARD: proot 未拦截 statx，ucf 临时文件校验失败会令 postinst 非零退出；'
      echo '# 此处 sshd 二进制与主机密钥已由 TVUbuntu 手动完成，直接退出 0 让 dpkg 标记为 installed。'
      echo 'if [ -x /usr/sbin/sshd ] && ls /etc/ssh/ssh_host_*_key >/dev/null 2>&1; then exit 0; fi'
    } > "${POSTINST}.new"
    tail -n +2 "$POSTINST" >> "${POSTINST}.new"
    mv -f "${POSTINST}.new" "$POSTINST"
    chmod 755 "$POSTINST" 2>/dev/null
    say "已为 openssh-server.postinst 注入守卫"
  fi
  # 重新配置，使 dpkg 状态落为 installed(ii)
  if dpkg --configure openssh-server >> "$HOSTLOG" 2>&1; then
    say "openssh-server 已标记为 installed (ii)，无 dpkg 中断提示"
  else
    say "WARNING: dpkg --configure 仍返回非 0，但 sshd 已可正常工作"
  fi
else
  say "openssh-server 状态=[$SSHD_PKG_STATUS]，已是 installed，无需 dpkg 收尾"
fi

# 最终校验
if [ -x /usr/sbin/sshd ]; then
  if /usr/sbin/sshd -t >> "$HOSTLOG" 2>&1; then
    say "sshd 配置校验通过 (sshd -t OK)"
  else
    say "WARNING: sshd -t 校验未通过，详见日志"
  fi
  say "sshd installed: YES"
  if command -v dropbear >/dev/null 2>&1; then
    say "dropbear installed: YES"
  else
    say "WARNING: dropbear 未安装（proot 模式 SSH 将由 run 脚本首次运行时补装）"
  fi
  exit 0
else
  say "sshd installed: NO"
  exit 1
fi
