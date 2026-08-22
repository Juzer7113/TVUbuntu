#!/system/bin/sh
# TVUbuntu Proot 模式启动脚本
# 无需 root，不调用 mount/chroot，全部走 proot 用户空间
# 调用: sh start_proot.sh <PORT> <root密码> <Ubuntu版本> <rootfs架构> <proot二进制路径>
# 例:  sh start_proot.sh 8022 Aa123456 22.04 arm64 /data/data/com.ubuntucontroller/lib/arm64/libproot.so
set +e

PORT="${1:-8022}"
SSH_PASS="${2:-Aa123456}"
VERSION="${3:-22.04}"
ARCH="${4:-arm64}"
PROOT_BIN="${5:-/data/data/com.ubuntucontroller/lib/arm64/libproot.so}"

ROOT=/data/data/com.ubuntucontroller/files/ubuntu
TMP="$ROOT/tmp"
VSAFE="$(echo "$VERSION" | sed 's/\./_/g')"
TAR="$ROOT/ubuntu-${VSAFE}-${ARCH}.tar"
FLAG="$ROOT/.installed.${VERSION}.${ARCH}"
ROOTFS="$ROOT/rootfs.${VERSION}.${ARCH}"
LOG="$ROOT/ubuntu.log"
SSH_UP="$ROOT/.ssh_up"
PIDFILE="$ROOT/run_ubuntu.pid"

# 校验输入
case "$VERSION" in
  22.04|24.04|26.04) ;;
  *) echo "ERROR: unsupported version $VERSION" >&2; exit 1 ;;
esac
case "$ARCH" in
  amd64|arm64|armhf) ;;
  *) echo "ERROR: unsupported arch $ARCH" >&2; exit 1 ;;
esac

# uname 机器名（用于 apt 源选择 / fakeuname）
case "$ARCH" in
  amd64|x86_64) MARCH="x86_64" ;;
  armhf|armv7l) MARCH="armhf" ;;
  *) MARCH="aarch64" ;;
esac

# 版本代号
case "$VERSION" in
  22.04) CODENAME="jammy" ;;
  24.04) CODENAME="noble" ;;
  26.04) CODENAME="resolute" ;;
  *) CODENAME="jammy" ;;
esac

progress() { echo "UC_PROGRESS|$1|$2"; }
log() { echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOG"; }

# 注意：proot 模式设计即不依赖 root。本固件 untrusted_app 域被 SELinux 拒绝执行
# app_data_file（guest bash/ld 所在类型），execve 返回 EACCES。此限制需在固件 sepolicy
# 构建期放行（见下方「诊断结论」），与运行时是否 root 无关。

# A4 修复：防重入锁。避免 BootService（开机广播）与 MainActivity（打开 App）并发触发
# 导致重复解包 / 抢端口。
# 原子锁：用 mkdir 做互斥（并发下只有一个实例能成功创建），目录内记录本实例 PID。
# 防 PID 复用误判：仅当锁内 PID 存活、且其 cmdline 确为本脚本时才视为活动实例；
# 实例异常退出（强杀/崩溃）导致的锁残留会自动清理，不会卡死后续启动。
# 判定顺序：锁（原子）→ dropbear/rootfs 进程（幂等跳过）。安装阶段 dropbear 尚未拉起，
# 由锁兜底，不会误杀正在进行的首次安装，也不会并发跑两份安装。
LOCK="$ROOT/.start_lock"
if mkdir "$LOCK" 2>/dev/null; then
  echo $$ > "$LOCK/pid" 2>/dev/null
  trap 'rm -rf "$LOCK" 2>/dev/null' EXIT
else
  LP=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$LP" ] && kill -0 "$LP" 2>/dev/null && grep -q "start_proot.sh" "/proc/$LP/cmdline" 2>/dev/null; then
    log "[proot] 已有启动实例 pid=$LP（安装/启动进行中），本次跳过重复启动"
    # 注意：此处进度不可报 100（另一实例仍在安装/启动，报 100 会误导 App 认为已就绪）
    progress 90 "正在安装/启动中（已有实例进行中），请稍候..."
    exit 0
  fi
  # 锁残留（持有者已死 / PID 已失效）：清掉后重试获取一次
  rm -rf "$LOCK"
  if mkdir "$LOCK" 2>/dev/null; then
    echo $$ > "$LOCK/pid" 2>/dev/null
    trap 'rm -rf "$LOCK" 2>/dev/null' EXIT
  else
    log "[proot] 获取启动锁失败（并发竞争），跳过"
    exit 0
  fi
fi
if pgrep -x dropbear >/dev/null 2>&1 || pgrep -f "rootfs\." >/dev/null 2>&1; then
  log "[proot] proot/dropbear 已在运行，跳过重复启动"
  progress 100 "Ubuntu 已启动（SSH 端口 $PORT）"
  exit 0
fi

mkdir -p "$ROOT" "$TMP" "$ROOTFS"
# proot 临时目录必须存在且可写（f2fs bug probe / loader 需要；toybox tar 可能丢权限）
chmod 1777 "$TMP" 2>/dev/null
rm -f "$SSH_UP"
: > "$LOG" 2>/dev/null

log "[proot] ===== 启动 proot 模式 ====="
log "[proot] PORT=$PORT VERSION=$VERSION ARCH=$ARCH MARCH=$MARCH CODENAME=$CODENAME"
log "[proot] PROOT_BIN=$PROOT_BIN"

if [ ! -f "$PROOT_BIN" ]; then
  log "[proot] ERROR: proot 二进制不存在: $PROOT_BIN"
  progress 100 "proot 二进制不存在"
  exit 1
fi

# proot 必须可执行；从 nativeLibraryDir 释放的一般已经是 755，但这里兜底
chmod 755 "$PROOT_BIN" 2>/dev/null

# 设置 proot 临时目录（默认 /tmp 在 Android 上不存在）
export PROOT_TMP_DIR="$TMP"

# 准备 DNS（bind 进 rootfs，不直接修改 rootfs 文件）
mkdir -p "$ROOTFS/etc" 2>/dev/null
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 114.114.114.114\nnameserver 8.8.8.8\n' > "$ROOT/resolv.conf"

# 关键目录兜底（toybox tar 可能丢失 1777 目录）
mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/run" "$ROOTFS/run/sshd" "$ROOTFS/run/lock" \
         "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/etc/apt" "$ROOTFS/etc/apt/sources.list.d" \
         "$ROOTFS/var/lib/apt/lists/partial" "$ROOTFS/var/cache/apt/archives/partial"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/run/lock" 2>/dev/null
chmod 755 "$ROOTFS/run" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" 2>/dev/null

# 安全路径校验（用于 symlink 修复）
is_safe_link() {
  local link="$1" target="$2"
  # link 必须是相对路径（tar 内条目均为相对）
  case "$link" in /*) return 1 ;; esac
  case "$link" in *..|*../*) return 1 ;; esac
  # target 允许：相对路径（含 ../ 或 .）或指向 guest 标准目录的绝对路径
  #   （/lib /usr /etc /bin /sbin /var /run /opt /tmp 等，proot 会在 guest 内正确解析）
  # 禁止：指向宿主目录的绝对路径（/data /system /proc /sys /dev /storage /sdcard ...），否则 proot 内会逃逸
  case "$target" in
    /data/*|/system/*|/proc/*|/sys/*|/dev/*|/storage/*|/sdcard/*|/vendor/*|/persist/*|/mnt/*) return 1 ;;
  esac
  return 0
}

# 修复 toybox tar 丢失的符号链接
fix_symlinks_from_tar() {
  local tar="$1"
  [ -f "$tar" ] || return
  command -v awk >/dev/null 2>&1 || return
  local tab
  tab=$(printf '\t')
  tar -tvf "$tar" 2>/dev/null | awk '/^l/ {
    match($0, /[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9] /)
    rest = substr($0, RSTART + RLENGTH)
    arrow = index(rest, " -> ")
    if (arrow > 0) {
      link = substr(rest, 1, arrow - 1)
      target = substr(rest, arrow + 4)
      print link "\t" target
    }
  }' | while IFS="$tab" read -r link target; do
    [ -n "$link" ] || continue
    if ! is_safe_link "$link" "$target"; then
      log "[proot] 跳过不安全 symlink: $link -> $target"
      continue
    fi
    local full="$ROOTFS/$link"
    local need_fix=0
    if [ ! -L "$full" ]; then need_fix=1
    elif [ "$(readlink "$full")" != "$target" ]; then need_fix=1
    fi
    if [ "$need_fix" = "1" ]; then
      rm -rf "$full" 2>/dev/null
      mkdir -p "$(dirname "$full")" 2>/dev/null
      if ln -s "$target" "$full" 2>/dev/null; then
        log "[proot] 修复 symlink: $link -> $target"
      else
        log "[proot] 修复 symlink 失败: $link -> $target"
      fi
    fi
  done
}

# 修复硬链接与 setuid 位
fix_tar_extras() {
  local tar="$1"
  [ -f "$tar" ] || return
  command -v awk >/dev/null 2>&1 || return
  local tab
  tab=$(printf '\t')
  tar -tvf "$tar" 2>/dev/null | awk '/^h/ {
    match($0, /[0-9][0-9]:[0-9][0-9] /)
    rest = substr($0, RSTART + RLENGTH)
    idx = index(rest, " link to ")
    if (idx > 0) print substr(rest, 1, idx-1) "\t" substr(rest, idx+9)
  }' | while IFS="$tab" read -r link target; do
    [ -n "$link" ] || continue
    if ! is_safe_link "$link" "$target"; then continue; fi
    if [ ! -e "$ROOTFS/$link" ] && [ -e "$ROOTFS/$target" ]; then
      ln "$ROOTFS/$target" "$ROOTFS/$link" 2>/dev/null && log "[proot] 修复硬链接: $link -> $target"
    fi
  done
  tar -tvf "$tar" 2>/dev/null | awk '$1 ~ /^-.{2}[sS]/ {
    match($0, /[0-9][0-9]:[0-9][0-9] /)
    print substr($0, RSTART + RLENGTH)
  }' | while read -r path; do
    [ -n "$path" ] || continue
    [ -e "$ROOTFS/$path" ] || continue
    chmod u+s "$ROOTFS/$path" 2>/dev/null && log "[proot] 修复 setuid: $path"
  done
}

# 修复 .so 库符号链接
fix_so_symlinks() {
  case "$MARCH" in
    x86_64) LIBDIR="$ROOTFS/usr/lib/x86_64-linux-gnu" ;;
    armhf)  LIBDIR="$ROOTFS/usr/lib/arm-linux-gnueabihf" ;;
    *)      LIBDIR="$ROOTFS/usr/lib/aarch64-linux-gnu" ;;
  esac
  [ -d "$LIBDIR" ] || return
  find "$LIBDIR" -maxdepth 1 -type f -name 'lib*.so.*.*' 2>/dev/null | while read -r real; do
    local base dir sox so
    base=$(basename "$real")
    dir=$(dirname "$real")
    sox=$(echo "$base" | sed 's/\.[0-9][0-9]*$//')
    [ "$sox" != "$base" ] || continue
    if [ ! -e "$dir/$sox" ]; then
      ln -s "$base" "$dir/$sox" 2>/dev/null && log "[proot] 修复 .so symlink: $sox -> $base"
    fi
    so=$(echo "$sox" | sed 's/\.[0-9][0-9]*$//')
    if [ "$so" != "$sox" ] && [ ! -e "$dir/$so" ]; then
      ln -s "$sox" "$dir/$so" 2>/dev/null || true
    fi
  done
}

# 修复 ELF 可执行位：toybox tar 解压可能丢失 x/setuid 位（setuid 已在 fix_tar_extras 修复）。
# 失败现象：proot error: execve("/usr/bin/bash"): Permission denied
# 原因：ld-linux 动态加载器或 ELF 二进制失去 x 位后，宿主内核以 App uid 真实检查权限 → EACCES。
# 需在 LD_LIBDIR/LD_SO 定义后调用（见首次安装流程）。
fix_exec_perms() {
  local ld="$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO"
  if [ -f "$ld" ]; then
    chmod 755 "$ld" 2>/dev/null
    log "[proot] 修复 ld 权限: $LD_SO = $(ls -l "$ld" 2>/dev/null | awk '{print $1}')"
  else
    log "[proot] WARNING: ld 文件不存在 $ld（ELF 加载器缺失，proot 无法启动）"
  fi
  chmod 755 "$ROOTFS/usr/bin" "$ROOTFS/usr/sbin" "$ROOTFS/usr/lib/$LD_LIBDIR" 2>/dev/null
  local f
  if command -v od >/dev/null 2>&1; then
    for f in "$ROOTFS"/usr/bin/* "$ROOTFS"/usr/sbin/*; do
      [ -f "$f" ] || continue
      # ELF 魔数 \x7fELF，仅对真实 ELF 加 x，避免误伤文本/脚本
      if [ "$(dd if="$f" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; then
        chmod 755 "$f" 2>/dev/null
      fi
    done
  else
    # 精简固件无 od：base rootfs 的 bin 目录几乎全为可执行 ELF，统一加 x（误伤面小）
    chmod 755 "$ROOTFS"/usr/bin/* "$ROOTFS"/usr/sbin/* 2>/dev/null
    log "[proot] od 不可用，已统一 chmod 755 /usr/bin /usr/sbin"
  fi
  log "[proot] ELF 可执行位修复完成"
}

# 首次安装：在 proot 内安装 openssh-server
if [ ! -f "$FLAG" ]; then
  if [ -f "$TAR" ]; then
    # 关键修复：之前只读取 tar 列表修复符号链接，却从未真正解包！
    # 必须先解压到 rootfs，否则 /bin/bash 等文件都不存在，proot 直接报 not found。
    progress 72 "正在解压 Ubuntu 系统文件（约需 1-2 分钟，请勿关闭）..."
    mkdir -p "$ROOTFS"
    # 若 rootfs 目录已存在但不完整（例如上次解包失败残留的悬空符号链接），
    # 先清空再解包，避免 tar 覆盖冲突
    if [ ! -x "$ROOTFS/bin/bash" ] && [ ! -e "$ROOTFS/usr/bin/bash" ]; then
      log "[proot] 检测到不完整的 rootfs，清理后重新解包"
      rm -rf "$ROOTFS"
      mkdir -p "$ROOTFS"
    fi
    tar -xf "$TAR" -C "$ROOTFS" 2>/dev/null
    if [ ! -x "$ROOTFS/bin/bash" ] && [ ! -e "$ROOTFS/bin/sh" ] && [ ! -e "$ROOTFS/usr/bin/bash" ]; then
      log "[proot] ERROR: tar 解压后未找到 /bin/bash，解压可能失败"
      progress 100 "解压失败，请重试或检查磁盘空间"
      exit 1
    fi
    log "[proot] tar 解压完成，bin/bash=$(test -x "$ROOTFS/bin/bash" && echo yes || echo NO), usr/bin/bash=$(test -x "$ROOTFS/usr/bin/bash" && echo yes || echo NO)"

    # 解包后再修复 tar 可能丢失/损坏的符号链接、硬链接、setuid 位
    progress 73 "正在修复 rootfs 符号链接..."
    fix_symlinks_from_tar "$TAR"
    fix_tar_extras "$TAR"
    fix_so_symlinks
  else
    log "[proot] ERROR: rootfs tar 不存在: $TAR"
    progress 100 "rootfs 未下载，请先联网下载系统"
    exit 1
  fi

  # 顶层 usrmerge 目录兜底
  for pair in "bin:usr/bin" "lib:usr/lib" "sbin:usr/sbin"; do
    link="${pair%%:*}"; target="${pair##*:}"
    if [ ! -L "$ROOTFS/$link" ] || [ "$(readlink "$ROOTFS/$link")" != "$target" ]; then
      rm -rf "$ROOTFS/$link" 2>/dev/null
      ln -s "$target" "$ROOTFS/$link" 2>/dev/null
      log "[proot] 兜底修复顶层 symlink: /$link -> $target"
    fi
  done
  if [ "$MARCH" = "x86_64" ]; then
    for pair in "lib64:usr/lib64" "lib32:usr/lib32" "libx32:usr/libx32"; do
      link="${pair%%:*}"; target="${pair##*:}"
      if [ ! -L "$ROOTFS/$link" ] || [ "$(readlink "$ROOTFS/$link")" != "$target" ]; then
        rm -rf "$ROOTFS/$link" 2>/dev/null
        ln -s "$target" "$ROOTFS/$link" 2>/dev/null
        log "[proot] 兜底修复顶层 symlink: /$link -> $target"
      fi
    done
  fi

  # 动态链接器兜底：确保 guest 加载器可被 proot 解析
  # 失败现象：proot error: execve("/usr/bin/bash"): No such file or directory
  # 原因：ELF interpreter 是 /lib64/ld-*.so，而 rootfs 内该路径缺失或指向不存在位置。
  #   对 x86_64 而言 ld 真实文件在 usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2，
  #   需保证 /lib64/ld... 经顶层 lib64->usr/lib64 之后能落到真实文件。
  case "$MARCH" in
    x86_64) LD_LIBDIR="x86_64-linux-gnu"; LD_SO="ld-linux-x86-64.so.2"; LD_DIRS="usr/lib64 usr/lib" ;;
    armhf)  LD_LIBDIR="arm-linux-gnueabihf"; LD_SO="ld-linux-armhf.so.3"; LD_DIRS="usr/lib" ;;
    *)      LD_LIBDIR="aarch64-linux-gnu"; LD_SO="ld-linux-aarch64.so.1"; LD_DIRS="usr/lib" ;;
  esac
  TRUE_LD="$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO"
  if [ -e "$TRUE_LD" ]; then
    for d in $LD_DIRS; do
      mkdir -p "$ROOTFS/$d" 2>/dev/null
      ln -sf "/usr/lib/$LD_LIBDIR/$LD_SO" "$ROOTFS/$d/$LD_SO" 2>/dev/null
      log "[proot] 兜底修复 ld symlink: /$d/$LD_SO -> /usr/lib/$LD_LIBDIR/$LD_SO"
    done
  else
    log "[proot] WARNING: 未找到真实 ld 文件 $TRUE_LD，加载器兜底跳过（proot 可能无法启动）"
  fi

  # 修复 ELF 可执行位（依赖上方 LD_LIBDIR/LD_SO，必须在此调用）
  fix_exec_perms

  # 诊断探针 v2：权限位已修复（755）后 execve 仍 EACCES，全面排查 SELinux / 文件完整性 / tmp / 挂载
  # 关键判定：
  #  - ld显式加载bash OK  → bash/ld 文件完好，限制在 execve/interpreter/SELinux 层
  #  - selinux_enforce=1  → SELinux enforcing 且 app_data_file 执行受限（需 root 关 SELinux 或改 root 模式）
  #  - bash_magic != 7f454c46 → bash 文件损坏（ENOEXEC）
  #  - tmp不可写 → proot 临时目录问题（需 -b tmp:/tmp 且 1777）
  SELINUX_CTX="$(cat /proc/self/attr/current 2>/dev/null || echo n/a)"
  BASH_PERM="$(ls -l "$ROOTFS/usr/bin/bash" 2>/dev/null | awk '{print $1}')"
  BASH_CTX="$(ls -Z "$ROOTFS/usr/bin/bash" 2>/dev/null | awk '{print $1}' || echo n/a)"
  BASH_MAGIC="$(dd if="$ROOTFS/usr/bin/bash" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')"
  LD_PERM="$(ls -l "$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" 2>/dev/null | awk '{print $1}')"
  SELINUX_ENFORCE="$(cat /sys/fs/selinux/enforce 2>/dev/null || echo n/a)"
  DATA_MOUNT="$(grep ' /data ' /proc/mounts 2>/dev/null | head -1 | awk '{print $4}')"
  log "[proot] 诊断: umask=$(umask 2>/dev/null)"
  log "[proot] 诊断: bash=$BASH_PERM ld=$LD_PERM"
  log "[proot] 诊断: bash_magic=$BASH_MAGIC"
  log "[proot] 诊断: selinux_enforce=$SELINUX_ENFORCE"
  log "[proot] 诊断: selinux_ctx=$SELINUX_CTX"
  log "[proot] 诊断: data_mount=$DATA_MOUNT"
  log "[proot] 诊断: bash_ctx=$BASH_CTX"
  if "$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" "$ROOTFS/usr/bin/bash" -c 'echo probe-ok' >/dev/null 2>&1; then
    log "[proot] 诊断: ld显式加载bash OK（bash文件完好，限制在execve/interpreter/SELinux层）"
  else
    log "[proot] 诊断: ld显式加载bash 失败 rc=$?（bash/ld文件损坏或执行受限）"
  fi
  if touch "$TMP/.probe" 2>/dev/null; then rm -f "$TMP/.probe" 2>/dev/null; log "[proot] 诊断: tmp可写OK"; else log "[proot] 诊断: tmp不可写!"; fi
  # 诊断结论（实锤判定）
  if [ "$BASH_PERM" = "-rwxr-xr-x" ] && [ "$BASH_MAGIC" = "7f454c46" ] \
     && echo "$SELINUX_CTX" | grep -q 'untrusted_app' \
     && echo "$BASH_CTX" | grep -q 'app_data_file'; then
    log "[proot] 诊断结论: SELinux 拒绝 untrusted_app 执行 app_data_file（EACCES=rc=126）。proot 模式本就无 root，此限制需固件侧放开，与运行时是否 root 无关。"
    log "[proot]   修复路径（均无需运行时 root，固件构建期施行）："
    log "[proot]   ① 固件 sepolicy 加: allow untrusted_app app_data_file:file { execute execute_no_trans };"
    log "[proot]      （若 AOSP neverallow 致编译失败，放宽/移除对应 neverallow，或为应用建独立域）"
    log "[proot]   ② 将本应用签名为 platform/system 特权应用（特权域默认可 exec app_data_file）"
    log "[proot]   ③ 自定义域 + file_contexts：把 /data/data/com.ubuntucontroller 标为可 exec 的 type"
  fi

  log "[proot] 首次安装，进入 rootfs 安装 SSH..."
  progress 75 "首次初始化 Ubuntu..."

  cp -f "$ROOT/install_ssh_proot.sh" "$ROOTFS/install_ssh_proot.sh" 2>/dev/null || {
    log "[proot] ERROR: install_ssh_proot.sh 未找到"
    progress 100 "安装脚本缺失"
    exit 1
  }

  # Ubuntu 26.04 兼容修复：26.04 的 coreutils 换成 Rust 版 multi-call（/usr/bin/coreutils，
  # 各工具是指向它的 symlink，靠 argv[0] 分发）。proot 用自定义 loader 加载程序时会把
  # argv[0] 改写成临时文件（prooted-*），导致 uutils 报 "unknown program"、mkdir/rm/ls 等
  # 全部失效、apt 无法工作。修复：预下载 GNU 独立版 coreutils（/usr/bin/gnu*，不依赖
  # argv[0]）+ libcap2，bind 进 rootfs 后由 install 脚本解包并把 uutils symlink 替换为 gnu*。
  if [ "$VERSION" = "26.04" ]; then
    mkdir -p "$ROOT/pkgs" 2>/dev/null
    case "$MARCH" in
      x86_64)  APT_MIRROR="http://archive.ubuntu.com/ubuntu";      APT_ARCH="amd64" ;;
      aarch64) APT_MIRROR="http://ports.ubuntu.com/ubuntu-ports";  APT_ARCH="arm64" ;;
      *)       APT_MIRROR="http://ports.ubuntu.com/ubuntu-ports";  APT_ARCH="armhf" ;;
    esac
    IDX="$ROOT/pkgs/Packages"
    if [ ! -s "$IDX" ]; then
      wget -qO "$IDX.gz" "$APT_MIRROR/dists/$CODENAME/main/binary-$APT_ARCH/Packages.gz" 2>/dev/null \
        && gzip -dc "$IDX.gz" > "$IDX" 2>/dev/null
      rm -f "$IDX.gz"
    fi
    get_deb_url() {
      grep -A 30 "^Package: $1$" "$IDX" 2>/dev/null | grep '^Filename:' | head -1 | sed 's/^Filename: *//'
    }
    for pair in "gnu-coreutils:gnu-coreutils.deb" "libcap2:libcap2.deb"; do
      pkg="${pair%%:*}"; out="$ROOT/pkgs/${pair##*:}"
      [ -s "$out" ] && continue
      url="$(get_deb_url "$pkg")"
      if [ -n "$url" ]; then
        log "[proot] 下载 $pkg (26.04 coreutils 修复)..."
        wget -qO "$out" "$APT_MIRROR/$url" 2>/dev/null || log "[proot] WARNING: $pkg 下载失败"
      else
        # 兜底：索引解析失败时用已知版本号（Resolute release）
        case "$pkg" in
          gnu-coreutils) wget -qO "$out" "$APT_MIRROR/pool/main/c/coreutils/gnu-coreutils_9.7-3ubuntu2_$APT_ARCH.deb" 2>/dev/null ;;
          libcap2)       wget -qO "$out" "$APT_MIRROR/pool/main/libc/libcap2/libcap2_2.75-10ubuntu2_$APT_ARCH.deb" 2>/dev/null ;;
        esac
      fi
    done
  fi

  # pkgs 仅 26.04 存在（上方按需创建）；22.04/24.04 动态为空，避免 proot bind 不存在的目录
  BIND_PKGS=""
  [ -d "$ROOT/pkgs" ] && BIND_PKGS="-b $ROOT/pkgs:/pkgs"

  rm -f "$ROOT/.install_exit"
  (
    export SSH_PASS CODENAME MARCH
    # -b /proc: 透传宿主 /proc（安装阶段 apt 不需要，保持与运行阶段一致；ps/htop 依赖，详见运行阶段说明）
    "$PROOT_BIN" -r "$ROOTFS" -0 -w /root \
      -b /dev -b /proc -b /sys \
      -b "$ROOT/resolv.conf:/etc/resolv.conf" \
      -b "$LOG:/hostlog" \
      -b "$TMP:/tmp" \
      $BIND_PKGS \
      /bin/bash /install_ssh_proot.sh
    echo $? > "$ROOT/.install_exit"
  ) >> "$LOG" 2>&1

  INSTALL_EXIT="$(cat "$ROOT/.install_exit" 2>/dev/null)"
  rm -f "$ROOT/.install_exit"
  rm -f "$ROOTFS/install_ssh_proot.sh"

  if [ "$INSTALL_EXIT" != "0" ]; then
    log "[proot] SSH 安装失败（exit=$INSTALL_EXIT）"
    progress 100 "SSH 安装失败，请查看日志"
    exit 1
  fi

  # A2 修复：proot 模式实际 SSH 服务是 dropbear（OpenSSH 在 proot 下会 SIGABRT 崩溃），
  # 旧逻辑误校验 /usr/sbin/sshd 会导致 openssh 半配置时误报「SSH 安装异常」。
  if [ ! -x "$ROOTFS/usr/sbin/dropbear" ]; then
    log "[proot] 校验失败：dropbear 未安装（/usr/sbin/dropbear 不存在）"
    progress 100 "SSH 安装异常"
    exit 1
  fi

  touch "$FLAG"
  progress 95 "Ubuntu 初始化完成，正在启动..."
else
  progress 80 "检测到已安装，正在启动 Ubuntu..."
fi

# 下发内部运行脚本：静态资产 + cp -f（与 install_ssh_proot.sh 同机制，设备上实证可靠）。
# 历史教训：此处曾用「运行时 heredoc 生成 + sed -i 替换占位符」，在 App 派生的 Android
# 系统 shell（无 /tmp、无可写 TMPDIR）下 heredoc 产出过 0 字节空文件，导致容器脚本
# 从未执行、proot 毫秒级退出 rc=0。现改为静态资产直接下发 + 环境变量定制，杜绝此类故障。
case "$MARCH" in
  x86_64) APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu" ;;
  *)      APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" ;;
esac

if [ ! -f "$ROOT/run_ubuntu_proot.sh" ]; then
  log "[proot] ERROR: 内部脚本资产缺失: $ROOT/run_ubuntu_proot.sh（请更新 APK）"
  progress 100 "内部脚本缺失，请更新 APK 后重试"
  exit 1
fi
cp -f "$ROOT/run_ubuntu_proot.sh" "$ROOTFS/run_ubuntu_proot.sh" 2>> "$LOG"
chmod 755 "$ROOTFS/run_ubuntu_proot.sh" 2>> "$LOG" || true

# 自检：下发后必须非空；空文件直接报错退出，绝不静默启动空容器
INNER_BYTES="$(wc -c < "$ROOTFS/run_ubuntu_proot.sh" 2>/dev/null | tr -d ' \t')"
SRC_BYTES="$(wc -c < "$ROOT/run_ubuntu_proot.sh" 2>/dev/null | tr -d ' \t')"
log "[proot] run_ubuntu_proot.sh 下发完成: ${INNER_BYTES:-0} 字节（源资产 ${SRC_BYTES:-?} 字节）"
if [ -z "$INNER_BYTES" ] || [ "$INNER_BYTES" = "0" ]; then
  log "[proot] ERROR: run_ubuntu_proot.sh 下发后为空文件，禁止启动容器"
  progress 100 "内部脚本下发失败（空文件），请查看日志"
  exit 1
fi

# 启动 proot 常驻进程
rm -f "$PIDFILE"
  (
  export SSH_PASS PORT CODENAME MARCH APT_BASE
  # -b /proc: 透传宿主 /proc 供容器内 ps/htop/top 使用；代价是容器内可读取宿主进程 cmdline
  # （proot 通病，无法经此杀宿主进程，仅信息泄露）。如更看重隔离可改为不绑定 /proc。
  "$PROOT_BIN" -r "$ROOTFS" -0 -w /root \
    -b /dev -b /proc -b /sys \
    -b "$ROOT/resolv.conf:/etc/resolv.conf" \
    -b "$LOG:/hostlog" \
    -b "$TMP:/tmp" \
    /bin/bash -c 'echo "[proot] bash wrapper 启动 pid=$$" >> /hostlog 2>/dev/null || true; exec /bin/bash /run_ubuntu_proot.sh'
  echo "[proot] proot 进程退出 rc=$?"
) >> "$LOG" 2>&1 &
echo $! > "$PIDFILE"
PID="$(cat "$PIDFILE" 2>/dev/null)"
log "[proot] proot 进程已启动 PID=$PID"

# 等待 sshd 就绪（多手段探活：端口监听 / 进程存在）
port_up() {
  if command -v ss >/dev/null 2>&1 && ss -tlnp 2>/dev/null | grep -q ":$PORT "; then return 0; fi
  if command -v netstat >/dev/null 2>&1 && netstat -tlnp 2>/dev/null | grep -q ":$PORT "; then return 0; fi
  # A1 修复：proot 模式实际 SSH 服务是 dropbear（OpenSSH 在 proot 下会崩溃），
  # 旧逻辑误探测 sshd 进程，在缺少 ss/netstat 的精简固件上会误报「SSH 未就绪」。现探测 dropbear。
  if pgrep -x dropbear >/dev/null 2>&1; then return 0; fi
  return 1
}
{
  echo "[proot-host] uname -m: $(uname -m)"
  echo "[proot-host] version: $VERSION / arch: $ARCH / codename: $CODENAME"
  echo "[proot-host] rootfs=$(test -d "$ROOTFS" && echo yes || echo NO)"
  echo "[proot-host] rootfs /bin/bash=$(test -x "$ROOTFS/bin/bash" && echo yes || echo NO)"
  echo "[proot-host] rootfs sshd=$(test -x "$ROOTFS/usr/sbin/sshd" && echo yes || echo NO) rootfs dropbear=$(test -x "$ROOTFS/usr/sbin/dropbear" && echo yes || echo NO)"

  i=0
  while [ $i -lt 60 ]; do
    if port_up; then
      echo up > "$SSH_UP"
      break
    fi
    sleep 1
    i=$((i+1))
  done
  echo "[proot-host] pgrep dropbear: $(pgrep -x dropbear 2>/dev/null | tr '\n' ' ' || echo none)"
  echo "[proot-host] listen $PORT: $(ss -tlnp 2>/dev/null | grep ":$PORT " || (netstat -tlnp 2>/dev/null | grep ":$PORT " || echo none))"

  # run_ubuntu_proot 内联日志与 sshd 调试信息已直接写入 ubuntu.log（/hostlog），
  # 此处提取关键行，便于一眼定位 sshd 失败原因
  {
    echo "===== [proot-host] run_ubuntu_proot 关键日志（[proot-inner]/sshd）====="
    grep -E '\[proot-inner\]|\[proot\]|sshd|ssh-keygen|(error|Error|fatal|FATAL|denied|refused|EPERM|Operation not permitted)' "$LOG" 2>/dev/null | tail -60
    echo "===== [proot-host] proot 进程退出记录 ====="
    grep -E '\[proot\] proot 进程退出' "$LOG" 2>/dev/null | tail -5
  } >> "$LOG" 2>&1
} >> "$LOG" 2>&1

if [ -f "$SSH_UP" ]; then
  progress 100 "Ubuntu 已启动（SSH 端口 $PORT，root 密码 $SSH_PASS）"
else
  progress 100 "Ubuntu 已启动，但 SSH 未就绪（请查看日志）"
fi
exit 0
