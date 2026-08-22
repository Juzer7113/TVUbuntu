#!/system/bin/sh
# TVUbuntu Proot-ADB 模式「宿主侧」启动脚本（在 adb shell 域运行）
#
# 与 start_proot.sh 的区别：本脚本由 App 经 adb 让 adbd 起 shell 域进程执行，
# 因此位于 shell 域、可 exec /data/local/tmp（shell_data_file）下的 guest ELF，
# 从而绕开「untrusted_app 域被 SELinux 拒绝执行 app_data_file」的固件限制。
#
# 所有文件都在 /data/local/tmp/ubuntu 下（shell 域可写可执行）：
#   ROOT     = /data/local/tmp/ubuntu
#   ROOTFS   = $ROOT/rootfs.$VERSION.$ARCH
#   TAR      = $ROOT/ubuntu-$VERSION-$ARCH.tar   （由 App 经 adb push 预置）
#   PROOT_BIN= $ROOT/libproot.so                 （由 App 经 adb push 预置并 chmod 755）
#   LOG      = $ROOT/ubuntu.log
# 参数：$1=SSH端口 $2=root密码 $3=Ubuntu版本 $4=架构
set +e
umask 022

PORT="${1:-8022}"
SSH_PASS="${2:-Aa123456}"
VERSION="${3:-22.04}"
ARCH="${4:-arm64}"
PROOT_BIN="/data/local/tmp/ubuntu/libproot.so"
ROOT="/data/local/tmp/ubuntu"
ROOTFS="$ROOT/rootfs.$VERSION.$ARCH"
TAR="$ROOT/ubuntu-$VERSION-$ARCH.tar"
LOG="$ROOT/ubuntu.log"
FLAG="$ROOT/.install_done"
SSH_UP="$ROOT/.ssh_up"

LD_LIBDIR=""
LD_SO=""
case "$ARCH" in
  armhf) LD_LIBDIR="arm-linux-gnueabihf"; LD_SO="ld-linux-armhf.so.3" ;;
  arm64) LD_LIBDIR="aarch64-linux-gnu"; LD_SO="ld-linux-aarch64.so.3" ;;
  amd64) LD_LIBDIR="x86_64-linux-gnu"; LD_SO="ld-linux-x86-64.so.2" ;;
  *) LD_LIBDIR="aarch64-linux-gnu"; LD_SO="ld-linux-aarch64.so.3" ;;
esac

log() { echo "[adb-proot] $1" >> "$LOG" 2>/dev/null || echo "[adb-proot] $1"; }
progress() { log "UC_PROGRESS|$1|$2"; }

log "===== start_proot_adb.sh 启动 $(date) ====="
log "PORT=$PORT VERSION=$VERSION ARCH=$ARCH ROOTFS=$ROOTFS"
progress 5 "准备 proot（adb 路径）环境…"

# resolv.conf 兜底：优先用设备自带 DNS，否则给公共 DNS（apt 安装需要联网）
if [ ! -f "$ROOT/resolv.conf" ]; then
  cp /etc/resolv.conf "$ROOT/resolv.conf" 2>/dev/null || \
    printf "nameserver 8.8.8.8\nnameserver 114.114.114.114\n" > "$ROOT/resolv.conf"
  log "resolv.conf 已就绪: $(cat "$ROOT/resolv.conf" 2>/dev/null | tr '\n' ' ')"
fi

if [ ! -x "$PROOT_BIN" ]; then
  log "ERROR: proot 二进制不可执行: $PROOT_BIN"
  progress 100 "proot 二进制缺失/不可执行"
  exit 1
fi
if [ ! -f "$TAR" ]; then
  log "ERROR: rootfs tar 不存在: $TAR（请确认 App 已推送）"
  progress 100 "rootfs tar 未推送"
  exit 1
fi

# 1) 解压 rootfs（幂等：已解压则跳过）
if [ ! -x "$ROOTFS/usr/bin/bash" ] && [ ! -e "$ROOTFS/bin/bash" ]; then
  progress 10 "正在解压 Ubuntu 系统文件…"
  mkdir -p "$ROOTFS"
  tar -xf "$TAR" -C "$ROOTFS" 2>/dev/null
  if [ ! -x "$ROOTFS/usr/bin/bash" ] && [ ! -e "$ROOTFS/bin/bash" ]; then
    log "ERROR: tar 解压后未找到 bash"
    progress 100 "解压失败"
    exit 1
  fi
  log "tar 解压完成，usr/bin/bash=$(test -x "$ROOTFS/usr/bin/bash" && echo yes || echo NO)"
else
  log "rootfs 已解压，跳过"
fi

# 2) 修复 ELF 可执行位（toybox tar 解压可能丢 x/setuid）
fix_exec_perms() {
  local ld="$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO"
  if [ -f "$ld" ]; then
    chmod 755 "$ld" 2>/dev/null
    log "修复 ld 权限: $LD_SO = $(ls -l "$ld" 2>/dev/null | awk '{print $1}')"
  else
    log "WARNING: ld 文件不存在 $ld"
  fi
  chmod 755 "$ROOTFS/usr/bin" "$ROOTFS/usr/sbin" "$ROOTFS/usr/lib/$LD_LIBDIR" 2>/dev/null
  local f
  if command -v od >/dev/null 2>&1; then
    for f in "$ROOTFS"/usr/bin/* "$ROOTFS"/usr/sbin/*; do
      [ -f "$f" ] || continue
      if [ "$(dd if="$f" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; then
        chmod 755 "$f" 2>/dev/null
      fi
    done
  else
    chmod 755 "$ROOTFS"/usr/bin/* "$ROOTFS"/usr/sbin/* 2>/dev/null
    log "od 不可用，已统一 chmod 755 /usr/bin /usr/sbin"
  fi
  log "ELF 可执行位修复完成"
}
fix_exec_perms

# 3) ld-linux 加载器回退软链（部分 rootfs 缺主加载器软链，proot 报错 not found）
fix_so_symlinks() {
  local d="$ROOTFS/usr/lib/$LD_LIBDIR"
  [ -d "$d" ] || return 0
  local real
  real="$(ls -1 "$d"/ld-linux-*.so.* 2>/dev/null | head -1)"
  if [ -n "$real" ]; then
    for s in ld-linux.so.3 ld-linux.so.2 ld-linux-$ARCH.so.3 ld-linux-$ARCH.so.2; do
      [ -e "$d/$s" ] && continue
      ln -sf "$(basename "$real")" "$d/$s" 2>/dev/null || true
    done
    log "ld-linux 软链回退: $(ls -l $d/ld-linux* 2>/dev/null | head -1)"
  fi
}
fix_so_symlinks

# 4) 把 install / run 脚本放进 rootfs（install_ssh_proot.sh / run_ubuntu_proot.sh 由 App 推送至 $ROOT）
cp -f "$ROOT/install_ssh_proot.sh" "$ROOTFS/install_ssh_proot.sh" 2>/dev/null
cp -f "$ROOT/run_ubuntu_proot.sh" "$ROOTFS/run_ubuntu_proot.sh" 2>/dev/null
# 注入 adb 路径标识，供 run 脚本区分日志等（保持兼容：run_ubuntu_proot.sh 已支持 /hostlog 绑定）
log "脚本已同步进 rootfs"

# 5) 首次安装 openssh/dropbear（在 proot 内执行，需联网 apt）
if [ ! -f "$FLAG" ]; then
  progress 30 "首次安装：在 proot 内安装 SSH 服务（需联网，约 1-3 分钟）…"
  export SSH_PASS CODENAME MARCH
  CODENAME_MAP="$VERSION"
  case "$VERSION" in
    22.04) CODENAME="jammy" ;;
    24.04) CODENAME="noble" ;;
    26.04) CODENAME="focal" ;;
    *) CODENAME="jammy" ;;
  esac
  MARCH_MAP="$ARCH"
  case "$ARCH" in
    arm64) MARCH="aarch64" ;;
    armhf) MARCH="armhf" ;;
    amd64) MARCH="x86_64" ;;
    *) MARCH="aarch64" ;;
  esac
  "$PROOT_BIN" -r "$ROOTFS" -0 -w /root \
    -b /dev -b /proc -b /sys \
    -b "$ROOT/resolv.conf:/etc/resolv.conf" \
    -b "$LOG:/hostlog" \
    /bin/bash /install_ssh_proot.sh
  if [ -x "$ROOTFS/usr/sbin/sshd" ] || command -v dropbear >/dev/null 2>&1; then
    touch "$FLAG"
    log "首次安装完成，写标记 $FLAG"
  else
    log "WARNING: 安装后未检测到 sshd/dropbear，可能网络受限，run 阶段将再次尝试"
  fi
else
  log "已安装过，跳过 apt 安装"
fi

# 6) 常驻启动 proot + dropbear（前台，保持 adb shell 流存活）
progress 80 "启动 proot + SSH（常驻）…"
export PORT SSH_PASS CODENAME MARCH
# 关键：前台 exec，不带 &，使 proot 生命周期绑定到本 adb shell 流。
# run_ubuntu_proot.sh 末尾是看门狗 while true 循环，bash 不退出 → proot 持续运行；
# App 关闭连接（CLSE）即结束本 shell，proot 随之退出（预期行为）。
exec "$PROOT_BIN" -r "$ROOTFS" -0 -w /root \
  -b /dev -b /proc -b /sys \
  -b "$ROOT/resolv.conf:/etc/resolv.conf" \
  -b "$LOG:/hostlog" \
  /bin/bash /run_ubuntu_proot.sh
