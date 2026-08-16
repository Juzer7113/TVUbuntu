#!/system/bin/sh
# Ubuntu 控制器 —— 一键安装并运行 Ubuntu（chroot 方案，需 root 权限）
# 前置: 已获取 root；rootfs 由 App 联网下载（不依赖盒子上的 curl/wget）
# 调用: sh start.sh <SSH端口> <root密码> <Ubuntu版本> <rootfs架构>
#   例: sh start.sh 22 Aa123456 22.04 arm64
# 兼容: 按 CPU 架构 + Ubuntu 版本区分 rootfs 目录；chroot 是内核原生，天然兼容 arm64/x86_64 与各 Ubuntu 版本
set +e

ROOT=/data/local/ubuntu

PORT="${1:-22}"
SSH_PASS="${2:-Aa123456}"
VERSION="${3:-22.04}"
ARCH="${4:-arm64}"

# 校验输入，防止路径注入/误删（P1-1）
case "$VERSION" in
  22.04|24.04|26.04) ;;
  *) echo "ERROR: unsupported version $VERSION" >&2; exit 1 ;;
esac
case "$ARCH" in
  amd64|arm64|armhf) ;;
  *) echo "ERROR: unsupported arch $ARCH" >&2; exit 1 ;;
esac

# 关键：以 App 传入的 $ARCH 为唯一架构依据，不再用 uname -m。
# 原因：MuMu 等模拟器会把 uname -m 伪装成 aarch64，导致错误下载 arm64 rootfs；
# 而真实内核是 x86_64，应下载 amd64 rootfs。App 端已用 getprop 综合判断真实架构。
# 支持架构：amd64(x86_64) / arm64(aarch64) / armhf(32位 ARM)。i386(32位 x86) 已被 Ubuntu 淘汰，无镜像。
case "$ARCH" in
  amd64|x86_64) MARCH="x86_64" ;;
  armhf|armv7l) MARCH="armhf" ;;
  *) MARCH="aarch64" ;;
esac

# 归一化：多架构库目录名 + 动态链接器文件名（用于 symlink 修复/拷贝/冒烟测试/诊断）
case "$MARCH" in
  x86_64) LD_LIBDIR="x86_64-linux-gnu"; LD_SO="ld-linux-x86-64.so.2" ;;
  armhf)  LD_LIBDIR="arm-linux-gnueabihf"; LD_SO="ld-linux-armhf.so.3" ;;
  *)      LD_LIBDIR="aarch64-linux-gnu"; LD_SO="ld-linux-aarch64.so.1" ;;
esac

# rootfs 目录 / 安装标记 / tar 文件名均按「版本+架构」隔离
ROOTFS="$ROOT/rootfs.${VERSION}.${ARCH}"
TMP="$ROOT/tmp"
FLAG="$ROOT/.installed.${VERSION}.${ARCH}"
VSAFE="$(echo "$VERSION" | sed 's/\./_/g')"
TAR="$ROOT/ubuntu-${VSAFE}-${ARCH}.tar"
SSH_UP="$ROOT/.ssh_up"

# 根据 Ubuntu 版本选 apt 源 codename
case "$VERSION" in
  22.04) CODENAME="jammy" ;;
  24.04) CODENAME="noble" ;;
  26.04) CODENAME="resolute" ;;
  *) CODENAME="jammy" ;;
esac

# 进度上报: UC_PROGRESS|<百分比>|<说明>
progress() { echo "UC_PROGRESS|$1|$2"; }

# 统一的日志追加函数
log() { echo "$(date '+%m-%d %H:%M:%S') $1"; }

# 清理上次的就绪标记，并清空本次运行日志
rm -f "$SSH_UP"
: > "$ROOT/ubuntu.log" 2>/dev/null

# SELinux 宽松模式：部分盒子 enforcing 会阻断挂载/文件操作
setenforce 0 2>/dev/null || true
log "[host] getenforce=$(getenforce 2>/dev/null || echo N/A) setenforce_rc=$?" >> "$ROOT/ubuntu.log"
log "[host] chroot=$(command -v chroot 2>/dev/null) type=$(ls -l "$(command -v chroot 2>/dev/null)" 2>&1 | head -1)" >> "$ROOT/ubuntu.log"
log "[host] busybox=$(command -v busybox 2>/dev/null || echo N/A) toybox=$(command -v toybox 2>/dev/null || echo N/A)" >> "$ROOT/ubuntu.log"

# 检查 chroot 命令（toybox/busybox 均提供）
if ! command -v chroot >/dev/null 2>&1; then
  progress 3 "缺少 chroot 命令，请确认已 root 且系统工具完整"
  log "[host] chroot 命令不存在" >> "$ROOT/ubuntu.log"
  exit 1
fi

# 默认用 chroot；冒烟测试若发现 busybox chroot 更可靠会切换
CHROOT_BIN="chroot"

mkdir -p "$ROOTFS" "$TMP"

# 判断某路径是否已挂载（-F 固定字符串匹配，避免路径中的 . 被当作正则）
is_mounted() { mount 2>/dev/null | grep -qF " $1 "; }

# 安全路径校验：拒绝绝对路径与逃离 ROOTFS 的相对路径（P1-2）
is_safe_link() {
  local link="$1" target="$2"
  case "$link" in /*) return 1 ;; esac
  case "$target" in /*) return 1 ;; esac
  case "$link" in *../*|*..) return 1 ;; esac
  case "$target" in *../*|*..) return 1 ;; esac
  return 0
}

# 挂载 proc/sys/dev 到 rootfs，并 bind 单个日志文件到 rootfs/hostlog（供容器内实时写宿主日志）
mount_system() {
  mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" 2>/dev/null
  is_mounted "$ROOTFS/proc" || mount -t proc proc "$ROOTFS/proc" 2>/dev/null
  is_mounted "$ROOTFS/sys"  || mount -t sysfs sysfs "$ROOTFS/sys" 2>/dev/null
  is_mounted "$ROOTFS/dev"  || { mount --bind /dev "$ROOTFS/dev" 2>/dev/null || mount -o bind /dev "$ROOTFS/dev" 2>/dev/null; }
  # /dev/pts：devpts 是 /dev 下的子挂载，bind /dev 不会自动带上它，需单独挂载，
  # 否则 SSH 登录时 posix_openpt 失败、分配不了伪终端（登录后终端异常）
  mkdir -p "$ROOTFS/dev/pts" 2>/dev/null
  is_mounted "$ROOTFS/dev/pts" || { mount --bind /dev/pts "$ROOTFS/dev/pts" 2>/dev/null || mount -t devpts devpts "$ROOTFS/dev/pts" 2>/dev/null; }
  # base rootfs 里没有 /dev/null，bind /dev 失败时用 mknod 兜底（apt/dpkg 重定向依赖它）
  [ -e "$ROOTFS/dev/null" ] || mknod "$ROOTFS/dev/null" c 1 3 2>/dev/null
  # bind 单个日志文件（不能 bind 整个 $ROOT，因为 rootfs 就在 $ROOT 下会形成循环挂载）
  touch "$ROOT/ubuntu.log" 2>/dev/null
  : > "$ROOTFS/hostlog" 2>/dev/null
  is_mounted "$ROOTFS/hostlog" || { mount --bind "$ROOT/ubuntu.log" "$ROOTFS/hostlog" 2>/dev/null || mount -o bind "$ROOT/ubuntu.log" "$ROOTFS/hostlog" 2>/dev/null; }
  log "[host] 挂载状态 proc=$(is_mounted "$ROOTFS/proc" && echo yes || echo no) dev=$(is_mounted "$ROOTFS/dev" && echo yes || echo no) pts=$(is_mounted "$ROOTFS/dev/pts" && echo yes || echo no) log=$(is_mounted "$ROOTFS/hostlog" && echo yes || echo no) null=$([ -e "$ROOTFS/dev/null" ] && echo yes || echo no)" >> "$ROOT/ubuntu.log"
}

# 卸载（安装完成后卸载，避免残留；启动阶段保持挂载供 sshd 使用）
umount_system() {
  umount "$ROOTFS/hostlog" 2>/dev/null
  umount "$ROOTFS/dev/pts" 2>/dev/null
  umount "$ROOTFS/dev" 2>/dev/null
  umount "$ROOTFS/sys" 2>/dev/null
  umount "$ROOTFS/proc" 2>/dev/null
}

# 通用超时执行：优先系统 timeout / busybox timeout，都没有时用后台+sleep 模拟超时
run_with_timeout() {
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$@"
    return $?
  elif command -v busybox >/dev/null 2>&1 && busybox timeout 0 true >/dev/null 2>&1; then
    busybox timeout "$secs" "$@"
    return $?
  fi
  "$@" &
  local pid=$!
  local i=0
  while [ $i -lt "$secs" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      return $?
    fi
    sleep 1
    i=$((i + 1))
  done
  kill "$pid" 2>/dev/null
  kill -9 "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  return 124
}

# 诊断：列出 rootfs 关键路径的实际情况（symlink / 文件 / 缺失）
diagnose_rootfs() {
  {
    echo "[diag] ===== rootfs 诊断开始 ($VERSION / $ARCH / uname=$MARCH) ====="
    echo "[diag] ROOTFS=$ROOTFS"
    echo "[diag] /bin 类型: $(ls -ld "$ROOTFS/bin" 2>&1)"
    echo "[diag] /lib 类型: $(ls -ld "$ROOTFS/lib" 2>&1)"
    echo "[diag] /sbin 类型: $(ls -ld "$ROOTFS/sbin" 2>&1)"
    if [ "$MARCH" = "x86_64" ]; then
      echo "[diag] /lib64 类型: $(ls -ld "$ROOTFS/lib64" 2>&1)"
    fi
    echo "[diag] /usr/bin/bash: $(ls -l "$ROOTFS/usr/bin/bash" 2>&1)"
    echo "[diag] /bin/bash: $(ls -l "$ROOTFS/bin/bash" 2>&1)"
    echo "[diag] /usr/bin/dash: $(ls -l "$ROOTFS/usr/bin/dash" 2>&1)"
    echo "[diag] /bin/sh: $(ls -l "$ROOTFS/bin/sh" 2>&1)"
    echo "[diag] 动态链接器:"
    if [ "$MARCH" = "x86_64" ]; then
      echo "[diag]   /lib64/$LD_SO: $(ls -l "$ROOTFS/lib64/$LD_SO" 2>&1)"
      echo "[diag]   /usr/lib64/$LD_SO: $(ls -l "$ROOTFS/usr/lib64/$LD_SO" 2>&1)"
    else
      echo "[diag]   /lib/$LD_SO: $(ls -l "$ROOTFS/lib/$LD_SO" 2>&1)"
      echo "[diag]   /usr/lib/$LD_SO: $(ls -l "$ROOTFS/usr/lib/$LD_SO" 2>&1)"
    fi
    echo "[diag]   /usr/lib/$LD_LIBDIR/$LD_SO: $(ls -l "$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" 2>&1)"
    echo "[diag] libc 文件: $(ls -l "$ROOTFS/usr/lib/$LD_LIBDIR/libc.so.6" 2>&1)"
    echo "[diag] 关键目录文件数:"
    echo "[diag]   /usr/bin: $(ls "$ROOTFS/usr/bin" 2>/dev/null | wc -l)"
    echo "[diag]   /usr/lib: $(ls "$ROOTFS/usr/lib" 2>/dev/null | wc -l)"
    if [ "$MARCH" = "x86_64" ]; then
      echo "[diag]   /usr/lib64: $(ls "$ROOTFS/usr/lib64" 2>/dev/null | wc -l)"
    fi
    echo "[diag]   /usr/lib/$LD_LIBDIR: $(ls "$ROOTFS/usr/lib/$LD_LIBDIR" 2>/dev/null | wc -l)"
    echo "[diag] 损坏的符号链接数: $(find "$ROOTFS/usr/lib" -type l ! -e 2>/dev/null | wc -l)"
    # ELF 魔数（前 20 字节十六进制），验证 bash/ld-linux 不是损坏或被 tar 解成普通文本
    echo "[diag] /usr/bin/bash ELF 头: $(od -A x -t x1 -N 20 "$ROOTFS/usr/bin/bash" 2>&1 | head -3 | tr '\n' ' ')"
    echo "[diag] ld-linux ELF 头: $(od -A x -t x1 -N 20 "$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" 2>&1 | head -3 | tr '\n' ' ')"
    # 测试 chroot 下不同路径/调用方式，定位是路径问题还是动态链接器解析问题
    echo "[diag] chroot 路径测试:"
    echo "[diag]   /bin/bash: $(chroot "$ROOTFS" /bin/bash -c 'echo PATH_BIN_BASH_OK' 2>&1 | tr '\n' ' ')"
    echo "[diag]   /usr/bin/bash: $(chroot "$ROOTFS" /usr/bin/bash -c 'echo PATH_USR_BIN_BASH_OK' 2>&1 | tr '\n' ' ')"
    echo "[diag]   /bin/sh: $(chroot "$ROOTFS" /bin/sh -c 'echo PATH_BIN_SH_OK' 2>&1 | tr '\n' ' ')"
    echo "[diag]   直接 ld-linux+bash: $(chroot "$ROOTFS" /usr/lib/$LD_LIBDIR/$LD_SO /bin/bash -c 'echo LDIRECT_OK' 2>&1 | tr '\n' ' ')"
    echo "[diag] ===== 宿主环境诊断 ====="
    echo "[diag] getenforce: $(getenforce 2>&1)"
    echo "[diag] /data 挂载项: $(grep ' /data ' /proc/mounts 2>/dev/null | head -1)"
    echo "[diag] chroot 文件: $(ls -l "$(command -v chroot 2>/dev/null)" 2>&1 | head -1)"
    echo "[diag] 宿主直接执行 ld-linux（不 chroot）:"
    echo "[diag]   $("$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" 2>&1 | head -2 | tr '\n' ' ')"
    # 决定性测试：chroot 后执行"宿主才有的" /system/bin/sh，若成功说明 chroot 根本没生效（no-op）
    echo "[diag] chroot+宿主sh（若输出 CHROOT_NOOP 则 chroot 失效）: $(chroot "$ROOTFS" /system/bin/sh -c 'echo CHROOT_NOOP' 2>&1 | head -1)"
    # 对照组：chroot 到宿主根再执行宿主 sh，验证 chroot+exec 本身可用
    echo "[diag] chroot / 宿主sh（应输出 CHROOT_HOST_OK）: $(chroot / /system/bin/sh -c 'echo CHROOT_HOST_OK' 2>&1 | head -1)"
    # SELinux 上下文（若 -Z 不支持会显示错误，忽略即可）
    echo "[diag] bash 上下文: $(ls -Z "$ROOTFS/usr/bin/bash" 2>&1 | head -1)"
    echo "[diag] ld-linux 上下文: $(ls -Z "$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO" 2>&1 | head -1)"
    echo "[diag] ===== rootfs 诊断结束 ====="
  } >> "$ROOT/ubuntu.log"
}

# 从 tarball 重新创建所有符号链接（解决 toybox tar 丢失 symlink 的问题）
fix_symlinks_from_tar() {
  local tar="$1"
  [ -f "$tar" ] || return
  # 需要 awk；toybox/busybox 通常都带
  if ! command -v awk >/dev/null 2>&1; then
    log "[install] 无 awk，跳过全量 symlink 修复" >> "$ROOT/ubuntu.log"
    return
  fi
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
      log "[install] 跳过不安全 symlink: $link -> $target" >> "$ROOT/ubuntu.log"
      continue
    fi
    local full="$ROOTFS/$link"
    local need_fix=0
    if [ ! -L "$full" ]; then
      need_fix=1
    elif [ "$(readlink "$full")" != "$target" ]; then
      need_fix=1
    fi
    if [ "$need_fix" = "1" ]; then
      rm -rf "$full" 2>/dev/null
      mkdir -p "$(dirname "$full")" 2>/dev/null
      if ln -s "$target" "$full" 2>/dev/null; then
        log "[install] 修复 symlink: $link -> $target" >> "$ROOT/ubuntu.log"
      else
        log "[install] 修复 symlink 失败: $link -> $target" >> "$ROOT/ubuntu.log"
      fi
    fi
  done
}

# 修复 toybox tar 可能丢失的硬链接和 setuid 位（tarball 里除 symlink 外的特殊条目）
fix_tar_extras() {
  local tar="$1"
  [ -f "$tar" ] || return
  command -v awk >/dev/null 2>&1 || return
  local tab
  tab=$(printf '\t')

  # 硬链接：tar -tvf 输出 "path link to target"
  tar -tvf "$tar" 2>/dev/null | awk '/^h/ {
    match($0, /[0-9][0-9]:[0-9][0-9] /)
    rest = substr($0, RSTART + RLENGTH)
    idx = index(rest, " link to ")
    if (idx > 0) print substr(rest, 1, idx-1) "\t" substr(rest, idx+9)
  }' | while IFS="$tab" read -r link target; do
    [ -n "$link" ] || continue
    if ! is_safe_link "$link" "$target"; then
      log "[install] 跳过不安全硬链接: $link -> $target" >> "$ROOT/ubuntu.log"
      continue
    fi
    if [ ! -e "$ROOTFS/$link" ] && [ -e "$ROOTFS/$target" ]; then
      ln "$ROOTFS/$target" "$ROOTFS/$link" 2>/dev/null && log "[install] 修复硬链接: $link -> $target" >> "$ROOT/ubuntu.log"
    fi
  done

  # setuid 文件（权限第 4 位是 s/S，如 -rwsr-xr-x）
  tar -tvf "$tar" 2>/dev/null | awk '$1 ~ /^-.{2}[sS]/ {
    match($0, /[0-9][0-9]:[0-9][0-9] /)
    print substr($0, RSTART + RLENGTH)
  }' | while read -r path; do
    [ -n "$path" ] || continue
    [ -e "$ROOTFS/$path" ] || continue
    chmod u+s "$ROOTFS/$path" 2>/dev/null && log "[install] 修复 setuid: $path" >> "$ROOT/ubuntu.log"
  done
}

# 启发式修复 .so 库符号链接：对每个 libfoo.so.X.Y.Z 创建 libfoo.so.X
LIBDIR="$ROOTFS/usr/lib/$LD_LIBDIR"
fix_so_symlinks() {
  [ -d "$LIBDIR" ] || return
  find "$LIBDIR" -maxdepth 1 -type f -name 'lib*.so.*.*' 2>/dev/null | while read -r real; do
    local base dir sox so
    base=$(basename "$real")
    dir=$(dirname "$real")
    # libfoo.so.X.Y.Z -> libfoo.so.X
    sox=$(echo "$base" | sed 's/\.[0-9][0-9]*$//')
    [ "$sox" != "$base" ] || continue
    if [ ! -e "$dir/$sox" ]; then
      ln -s "$base" "$dir/$sox" 2>/dev/null && log "[install] 修复 .so symlink: $sox -> $base" >> "$ROOT/ubuntu.log"
    fi
    # libfoo.so.X -> libfoo.so (开发包常需要，但运行时一般不需要；顺手补上)
    so=$(echo "$sox" | sed 's/\.[0-9][0-9]*$//')
    if [ "$so" != "$sox" ] && [ ! -e "$dir/$so" ]; then
      ln -s "$sox" "$dir/$so" 2>/dev/null || true
    fi
  done
}

# 尝试用指定命令在 chroot 里输出标记，限时 8s；依次尝试 chroot / busybox chroot
_chroot_try() {
  local marker="$1"; shift
  local out="$ROOT/chroot_try_$marker.log"
  local cb
  for cb in "chroot" "busybox chroot"; do
    case "$cb" in
      "busybox chroot") command -v busybox >/dev/null 2>&1 || continue ;;
    esac
    : > "$out"
    $cb "$ROOTFS" "$@" > "$out" 2>&1 &
    local pid=$!
    local ok=0
    local i=0
    while [ $i -lt 8 ]; do
      if grep -q "$marker" "$out" 2>/dev/null; then ok=1; break; fi
      if ! kill -0 "$pid" 2>/dev/null; then break; fi
      sleep 1
      i=$((i + 1))
    done
    kill "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    # 进程退出后最后再查一次：bash 可能刚写完 marker 就退出，循环里 grep 会错过（竞态）
    [ "$ok" = "1" ] || { grep -q "$marker" "$out" 2>/dev/null && ok=1; }
    if [ "$ok" = "1" ]; then
      echo "OK:$cb"
      return
    fi
  done
  cat "$out" 2>/dev/null | head -3 | tr '\n' ' '
}

# 把动态链接器拷贝到解释器路径，避免某些内核/toybox 无法解析 symlink 链
copy_ld_to_interp_path() {
  local src="$ROOTFS/usr/lib/$LD_LIBDIR/$LD_SO"
  local dst
  case "$MARCH" in
    x86_64) dst="$ROOTFS/usr/lib64/$LD_SO" ;;
    *) dst="$ROOTFS/usr/lib/$LD_SO" ;;
  esac
  if [ -f "$src" ]; then
    cp -af "$src" "$dst" 2>/dev/null
    log "[install] 拷贝 ld-linux 到 $dst" >> "$ROOT/ubuntu.log"
  fi
}

# 对 rootfs 执行 chroot 冒烟测试，验证 /bin/bash 能真正跑起来
chroot_smoke() {
  local r
  r=$(_chroot_try CHROOT_BASH_OK /bin/bash -c 'echo CHROOT_BASH_OK')
  case "$r" in
    OK:*)
      CHROOT_BIN="${r#OK:}"
      log "[install] chroot /bin/bash 冒烟测试: OK (via $CHROOT_BIN)" >> "$ROOT/ubuntu.log"
      return 0
      ;;
  esac
  log "[install] chroot /bin/bash 冒烟测试: FAILED ($r)" >> "$ROOT/ubuntu.log"

  # 尝试直接调用 ld-linux + bash，确认文件本身没问题
  LDREAL="/usr/lib/$LD_LIBDIR/$LD_SO"
  r=$(_chroot_try LDIRECT_OK "$LDREAL" /bin/bash -c 'echo LDIRECT_OK')
  case "$r" in
    OK:*)
      CHROOT_BIN="${r#OK:}"
      log "[install] 直接调用 ld-linux 可运行，判断为解释器路径 symlink 解析问题，执行拷贝兜底" >> "$ROOT/ubuntu.log"
      copy_ld_to_interp_path
      r=$(_chroot_try CHROOT_BASH_OK2 /bin/bash -c 'echo CHROOT_BASH_OK2')
      case "$r" in
        OK:*)
          CHROOT_BIN="${r#OK:}"
          log "[install] 拷贝 ld-linux 后 /bin/bash 冒烟测试: OK (via $CHROOT_BIN)" >> "$ROOT/ubuntu.log"
          return 0
          ;;
      esac
      log "[install] 拷贝 ld-linux 后仍失败: $r" >> "$ROOT/ubuntu.log"
      ;;
    *)
      log "[install] 直接调用 ld-linux 也失败: $r" >> "$ROOT/ubuntu.log"
      ;;
  esac

  # 兜底：尝试修正 SELinux 上下文为可执行（部分盒子 shell_data_file 不允许 exec）
  if command -v chcon >/dev/null 2>&1; then
    log "[install] 尝试 chcon 修正可执行上下文…" >> "$ROOT/ubuntu.log"
    chcon -R u:object_r:system_file:s0 "$ROOTFS/usr/bin" "$ROOTFS/usr/sbin" "$ROOTFS/usr/lib" "$ROOTFS/lib" "$ROOTFS/bin" "$ROOTFS/sbin" 2>/dev/null
    r=$(_chroot_try CHROOT_BASH_OK3 /bin/bash -c 'echo CHROOT_BASH_OK3')
    case "$r" in
      OK:*)
        CHROOT_BIN="${r#OK:}"
        log "[install] chcon 修正上下文后 /bin/bash 冒烟测试: OK (via $CHROOT_BIN)" >> "$ROOT/ubuntu.log"
        return 0
        ;;
    esac
    log "[install] chcon 修正后仍失败: $r" >> "$ROOT/ubuntu.log"
  else
    log "[install] 无 chcon 命令，跳过上下文修正" >> "$ROOT/ubuntu.log"
  fi

  diagnose_rootfs
  return 1
}

# 首次安装 rootfs + 基础环境（纯 tar 由 App 下载并解压 gzip 后落到 $TAR）
if [ ! -f "$FLAG" ]; then
  log "[install] 首次安装开始（Ubuntu $VERSION / arch=$ARCH / uname=$MARCH）" >> "$ROOT/ubuntu.log"

  if [ ! -f "$TAR" ]; then
    progress 70 "rootfs 缺失，尝试在设备内下载…"
    log "[install] App 未下载到 $TAR，尝试设备内下载" >> "$ROOT/ubuntu.log"
    case "$ARCH" in
      amd64) URL_ARCH="amd64" ;;
      armhf) URL_ARCH="armhf" ;;
      *) URL_ARCH="arm64" ;;
    esac
    case "$VERSION" in
      22.04) FILE="ubuntu-base-22.04.4-base-${URL_ARCH}.tar.gz" ;;
      24.04) FILE="ubuntu-base-24.04.4-base-${URL_ARCH}.tar.gz" ;;
      26.04) FILE="ubuntu-base-26.04-base-${URL_ARCH}.tar.gz" ;;
      *) FILE="ubuntu-base-22.04.4-base-${URL_ARCH}.tar.gz" ;;
    esac
    URL="https://cdimage.ubuntu.com/ubuntu-base/releases/$VERSION/release/$FILE"
    TGZ="$ROOT/ubuntu.tar.gz"
    rm -f "$TGZ"
    if command -v wget >/dev/null 2>&1; then
      run_with_timeout 120 wget -O "$TGZ" "$URL" 2>&1 | tail -5 >> "$ROOT/ubuntu.log"
    elif command -v busybox >/dev/null 2>&1; then
      run_with_timeout 120 busybox wget -O "$TGZ" "$URL" 2>&1 | tail -5 >> "$ROOT/ubuntu.log"
    elif command -v curl >/dev/null 2>&1; then
      run_with_timeout 120 curl -sL -o "$TGZ" "$URL" 2>&1 | tail -5 >> "$ROOT/ubuntu.log"
    fi
    if [ ! -s "$TGZ" ]; then
      log "[install] rootfs 未下载：请通过 App 联网安装（App 会自行下载系统）" >> "$ROOT/ubuntu.log"
      exit 1
    fi
    if command -v gzip >/dev/null 2>&1; then
      gzip -dc "$TGZ" > "$TAR" 2>/dev/null
    elif command -v busybox >/dev/null 2>&1; then
      busybox gzip -dc "$TGZ" > "$TAR" 2>/dev/null
    elif command -v zcat >/dev/null 2>&1; then
      zcat "$TGZ" > "$TAR" 2>/dev/null
    fi
    rm -f "$TGZ"
    if [ ! -s "$TAR" ]; then
      log "[install] rootfs 解压失败：盒子上缺少 gzip 工具，请通过 App 联网安装" >> "$ROOT/ubuntu.log"
      exit 1
    fi
  fi

  progress 75 "正在解压 rootfs…"
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  # -p 保留权限，避免 toybox tar 默认降权
  tar -xpf "$TAR" -C "$ROOTFS" 2>> "$ROOT/ubuntu.log" || {
    log "[install] 解压失败（tar 解包失败），tar=$TAR" >> "$ROOT/ubuntu.log"
    rm -f "$TAR"
    exit 1
  }
  # 保留 $TAR，下次启动时 App 检测到已存在就不会再下载（实现"安装后不再重复下载"）

  # 确保关键系统目录存在且权限正确：toybox tar 有时会丢失 1777(sticky) 目录（如 /tmp），
  # 导致 apt 验证签名时 mkstemp 报 "No such file or directory"
  mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/run/lock" "$ROOTFS/run/sshd" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
  chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/run/lock" 2>/dev/null
  chmod 755 "$ROOTFS/run" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" 2>/dev/null

  # 修复符号链接：部分盒子的 toybox tar 解压 symlink 会失败（跳过或解成普通文件），
  # 导致 chroot 后 /bin/bash、/lib/ld-linux 找不到（报 No such file or directory）。
  # 1) 先按 tarball 原样重建所有 symlink
  fix_symlinks_from_tar "$TAR"
  # 2) 修复硬链接和 setuid 位（toybox tar 也可能丢失这两类）
  fix_tar_extras "$TAR"
  # 3) 再对 .so 库做启发式兜底修复
  fix_so_symlinks

  # 4) 顶层 usrmerge 目录兜底（按架构）
  for pair in "bin:usr/bin" "lib:usr/lib" "sbin:usr/sbin"; do
    link="${pair%%:*}"; target="${pair##*:}"
    if [ ! -L "$ROOTFS/$link" ] || [ "$(readlink "$ROOTFS/$link")" != "$target" ]; then
      rm -rf "$ROOTFS/$link" 2>/dev/null
      ln -s "$target" "$ROOTFS/$link" 2>/dev/null
      log "[install] 兜底修复顶层 symlink: /$link -> $target" >> "$ROOT/ubuntu.log"
    fi
  done
  # x86_64 额外需要 /lib64 /lib32 /libx32
  if [ "$MARCH" = "x86_64" ]; then
    for pair in "lib64:usr/lib64" "lib32:usr/lib32" "libx32:usr/libx32"; do
      link="${pair%%:*}"; target="${pair##*:}"
      if [ ! -L "$ROOTFS/$link" ] || [ "$(readlink "$ROOTFS/$link")" != "$target" ]; then
        rm -rf "$ROOTFS/$link" 2>/dev/null
        ln -s "$target" "$ROOTFS/$link" 2>/dev/null
        log "[install] 兜底修复顶层 symlink: /$link -> $target" >> "$ROOT/ubuntu.log"
      fi
    done
  fi

  # 4) 动态链接器 symlink 兜底（x86_64 在 usr/lib64，arm64/armhf 在 usr/lib）
  case "$MARCH" in
    x86_64) LDLINK="usr/lib64/$LD_SO" ;;
    *) LDLINK="usr/lib/$LD_SO" ;;
  esac
  if [ ! -L "$ROOTFS/$LDLINK" ] || [ ! -e "$ROOTFS/$LDLINK" ]; then
    rm -f "$ROOTFS/$LDLINK" 2>/dev/null
    ln -s "$LD_LIBDIR/$LD_SO" "$ROOTFS/$LDLINK" 2>/dev/null
    log "[install] 兜底修复 ld symlink: /$LDLINK -> $LD_LIBDIR/$LD_SO" >> "$ROOT/ubuntu.log"
  fi

  # chroot 冒烟测试（先不挂载，验证 chroot 本身 + /bin/bash 可执行）
  # 若这里就失败，说明是内核 chroot+exec 兼容性问题，挂不挂载都一样
  if ! chroot_smoke; then
    log "[install] rootfs 无法启动 shell，已清理安装标记" >> "$ROOT/ubuntu.log"
    rm -f "$FLAG"
    progress 100 "rootfs 初始化失败，请复制日志查看诊断"
    exit 1
  fi

  # DNS 解析（写入 rootfs，让容器内能联网）——国内优先
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 114.114.114.114\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
  log "[install] resolv.conf 写入完成" >> "$ROOT/ubuntu.log"

  progress 78 "准备安装 SSH 服务…"
  progress 85 "正在安装 SSH 服务…"

  # 安装脚本写进 rootfs 独立文件；进度实时写宿主日志（经 /host bind mount）
  cat > "$ROOTFS/install_ssh.sh" <<'INSTALL'
#!/bin/bash
set +e
# 关键：chroot 后 PATH 继承的是 Android 的 /system/bin，必须显式设回 Ubuntu 标准路径，
# 否则 apt-get/dpkg/rm/mkdir/sed/ssh-keygen 等命令都找不到
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export LC_ALL=C
export LANG=C

# 宿主日志（经 bind 单个文件，容器内 /hostlog = 宿主 /data/local/ubuntu/ubuntu.log）
HOSTLOG="/hostlog"
# 优先写 bind 的 /hostlog（实时）；bind 失败时才回退 stdout（被宿主捕获）
say() { echo "[install] $1" >> "$HOSTLOG" 2>/dev/null || echo "[install] $1"; }

say "容器 shell 就绪"

# 兜底：确保 /tmp、/var/tmp、/etc/apt 等关键目录存在（toybox tar 可能丢失 1777 目录，apt 需要 /tmp）
mkdir -p /tmp /var/tmp /run/sshd /run/lock /etc/apt /etc/apt/sources.list.d /var/lib/apt/lists/partial /var/cache/apt/archives/partial
chmod 1777 /tmp /var/tmp /run/lock 2>/dev/null
chmod 755 /run 2>/dev/null
say "关键路径检查: /tmp=$(test -d /tmp && echo OK || echo MISS) /etc/apt=$(test -d /etc/apt && echo OK || echo MISS) apt-get=$(test -x /usr/bin/apt-get && echo OK || echo MISS) df=$(df -h / 2>/dev/null | tail -1 | tr -s ' ')"

# 根据架构选择 apt 源：amd64 用 ubuntu 主源，arm64 用 ubuntu-ports 源。
# $MARCH 由宿主 start.sh export 传入（x86_64 / aarch64）。
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

# 通用 apt 选项：强制 IPv4、不校验 Release 有效期、限制超时避免挂起
APT_OPTS="-o Acquire::ForceIPv4=true -o Acquire::Check-Valid-Until=false -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::ftp::Timeout=30 -o Acquire::Retries=2"

write_sources() {
  local base="$1"
  local codename="$2"
  mkdir -p /etc/apt /etc/apt/sources.list.d 2>/dev/null
  cat > /etc/apt/sources.list <<EOF
deb $base $codename main restricted universe multiverse
deb $base ${codename}-updates main restricted universe multiverse
deb $base ${codename}-security main restricted universe multiverse
EOF
}

say "清理 apt/dpkg 锁与残留源"
rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock* 2>/dev/null
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* 2>/dev/null
# Ubuntu 24.04+ 用 deb822 格式的 sources.list.d/ubuntu.sources 指向官方源，必须删掉
rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null
mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /run/sshd

say "dpkg --configure -a"
dpkg --configure -a >> "$HOSTLOG" 2>&1

say "apt-get update (tuna)"
write_sources "$MIRROR_TUNA" "$CODENAME"
if ! apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1; then
  say "tuna 失败，回退 aliyun"
  write_sources "$MIRROR_ALIYUN" "$CODENAME"
  if ! apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1; then
    say "aliyun 失败，回退 official"
    write_sources "$MIRROR_OFFICIAL" "$CODENAME"
    apt-get update -y --allow-releaseinfo-change $APT_OPTS >> "$HOSTLOG" 2>&1
  fi
fi

say "apt-get install openssh-server + 常用工具"
# 一次性预装常用工具（ip/nano/sudo/reboot 等），装进持久化 rootfs，之后启动不再重复装
if ! apt-get install -y --no-install-recommends openssh-server \
    curl wget git vim htop nano sudo unzip ca-certificates \
    iproute2 net-tools sysvinit-utils $APT_OPTS >> "$HOSTLOG" 2>&1; then
  say "ERROR: apt-get install 失败"
  exit 1
fi

# ===== 修复 uname 架构伪装（MuMu 等 x86 模拟器把 uname -m 伪装成 aarch64）=====
# 根因：模拟器内核是 x86_64，但为兼容 ARM 应用把 uname 伪装成 aarch64，
# 导致 Python platform.machine() 返回 aarch64，pip 下载 aarch64 wheel，宝塔 C 扩展加载失败。
# 方案：编译一个 LD_PRELOAD 库 hook uname()，把 machine 字段强制返回真实 x86_64。
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
    # 先单进程冒烟测试，确认无误再全局生效，避免 .so 有问题导致系统起不来
    smoke="$(LD_PRELOAD=/usr/local/lib/fakeuname.so uname -m 2>/dev/null)"
    if [ "$smoke" = "x86_64" ]; then
      echo "/usr/local/lib/fakeuname.so" > /etc/ld.so.preload
      say "fakeuname 修复完成：uname -m 现返回 $(uname -m)"
    else
      say "fakeuname 冒烟测试未通过（got: $smoke），不全局生效"
      rm -f /usr/local/lib/fakeuname.so
    fi
  else
    say "fakeuname 编译失败，跳过（不影响 SSH，仅 Python/pip 架构识别受影响）"
  fi
else
  say "uname 架构正常（$(uname -m)），无需 fakeuname"
fi

mkdir -p /run/sshd
ssh-keygen -A >> "$HOSTLOG" 2>&1
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config

# 密码只在「首次安装」时初始化一次，之后启动不再自动重置（否则用户 SSH 改的密码会被覆盖）
if [ -n "$SSH_PASS" ] && [ ! -f /etc/.ssh_password_initialized ]; then
  if echo "root:$SSH_PASS" | chpasswd >> "$HOSTLOG" 2>&1; then
    touch /etc/.ssh_password_initialized
    say "root 密码已初始化（仅首次）"
  else
    say "root 密码初始化失败"
  fi
fi

if [ -x /usr/sbin/sshd ]; then
  say "sshd installed: YES"
  exit 0
else
  say "sshd installed: NO"
  exit 1
fi
INSTALL
  chmod 755 "$ROOTFS/install_ssh.sh"

  export SSH_PASS CODENAME MARCH
  log "[install] 开始 apt 安装 openssh-server（$VERSION / $CODENAME）" >> "$ROOT/ubuntu.log"

  # 挂载系统目录 + 宿主日志绑定，chroot 执行安装；用「退出标记文件」判定结束
  mount_system
  rm -f "$ROOT/.install_exit"
  ( $CHROOT_BIN "$ROOTFS" /bin/bash /install_ssh.sh; echo "$?" > "$ROOT/.install_exit" ) >> "$ROOT/ubuntu.log" 2>&1 &
  INSTALL_PID=$!
  i=0
  while [ $i -lt 480 ]; do
    if [ -f "$ROOT/.install_exit" ]; then break; fi
    if [ $((i % 15)) -eq 0 ] && [ $i -gt 0 ]; then
      progress $((86 + i / 30)) "正在安装 SSH 服务…（已 ${i}s，首次需下载约 40MB 依赖，请耐心等待）"
    fi
    sleep 1
    i=$((i + 1))
  done

  if [ ! -f "$ROOT/.install_exit" ]; then
    kill "$INSTALL_PID" 2>/dev/null
    kill -9 "$INSTALL_PID" 2>/dev/null
    umount_system
    log "[install] SSH 服务安装超时（480s），已清理安装标记" >> "$ROOT/ubuntu.log"
    rm -f "$FLAG"
    progress 100 "SSH 服务安装超时，请检查网络后重试"
    exit 1
  fi

  INSTALL_EXIT="$(cat "$ROOT/.install_exit" 2>/dev/null)"
  rm -f "$ROOT/.install_exit"
  umount_system
  if [ "$INSTALL_EXIT" != "0" ]; then
    log "[install] SSH 服务安装失败（exit=$INSTALL_EXIT），已清理安装标记，下次将重新安装" >> "$ROOT/ubuntu.log"
    rm -f "$FLAG"
    progress 100 "SSH 服务安装失败，请查看日志或检查网络"
    exit 1
  fi

  if [ ! -x "$ROOTFS/usr/sbin/sshd" ]; then
    log "[install] 校验失败：/usr/sbin/sshd 不存在" >> "$ROOT/ubuntu.log"
    rm -f "$FLAG"
    progress 100 "SSH 安装异常，请查看日志"
    exit 1
  fi

  touch "$FLAG"
  progress 95 "Ubuntu 安装完成，正在启动…"
else
  progress 80 "检测到已安装，正在启动 Ubuntu…"
fi

# 启动 Ubuntu + sshd（chroot 后台常驻）
export PORT SSH_PASS

cat > "$ROOTFS/run_ubuntu.sh" <<'INNER'
#!/bin/bash
set +e
# Android 的 shell umask 可能是 000，会让 mkdir 建出 777 目录，导致 sshd 拒绝启动
umask 022
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
echo "[inner] PATH=$PATH"
echo "[inner] sshd bin: $(command -v sshd || ls -l /usr/sbin/sshd 2>&1)"
mkdir -p /run/sshd
chmod 755 /run /run/sshd 2>/dev/null
chown root:root /run/sshd 2>/dev/null
if [ ! -x /usr/sbin/sshd ]; then
  echo "[inner] 未找到 sshd，尝试 apt 安装（fallback）..."
  export DEBIAN_FRONTEND=noninteractive
  export DEBCONF_NONINTERACTIVE_SEEN=true
  [ -s /etc/apt/sources.list ] || cat > /etc/apt/sources.list <<'EOF'
deb __APT_BASE__ CODENAME main restricted universe multiverse
deb __APT_BASE__ CODENAME-updates main restricted universe multiverse
deb __APT_BASE__ CODENAME-security main restricted universe multiverse
EOF
  rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null
  apt-get update -y 2>&1 | tail -5
  apt-get install -y --no-install-recommends openssh-server 2>&1 | tail -8
  # fallback 安装也遵守「密码只初始化一次」，不覆盖用户已改的密码
  if [ -n "$SSH_PASS" ] && [ ! -f /etc/.ssh_password_initialized ]; then
    echo "root:$SSH_PASS" | chpasswd 2>&1 && touch /etc/.ssh_password_initialized
  fi
fi
echo "[inner] ssh-keygen -A ..."
ssh-keygen -A 2>&1 | tail -8
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
# 注意：这里不再 chpasswd —— 密码仅在首次安装时初始化一次，避免每次启动重置用户已改的密码
echo "[inner] sshd 配置自检 (sshd -t):"
/usr/sbin/sshd -t 2>&1 | tail -15

# ===== 生产化：服务自启动 =====
# chroot 里没有 systemd，用以下三层机制管理服务（架构无关，所有 Ubuntu 版本通用）：
# 1) /etc/rc.local —— 用户自定义启动脚本，后台执行（可放额外启动命令）
if [ -x /etc/rc.local ]; then
  echo "[inner] 执行 /etc/rc.local ..."
  nohup /etc/rc.local >> /hostlog 2>&1 &
fi
# 2) supervisord —— 若已安装，用 supervisor 统一管理服务
if [ -x /usr/bin/supervisord ]; then
  echo "[inner] 启动 supervisord ..."
  /usr/bin/supervisord -c /etc/supervisor/supervisord.conf 2>&1
fi
# 3) 通用服务自启动 —— 自动遍历 /etc/init.d/ 下所有服务脚本逐个 start，
#    拉起宝塔/nginx/php/mysql/redis/自定义服务；跳过「关机/挂载/杀进程」等危险系统脚本。
#    以后装任何带 init.d 脚本的服务都会自动开机启动，无需改脚本。
DANGEROUS_INIT="halt reboot poweroff shutdown killall single sendsigs killprocs umountfs umountroot umountnfs umountiscsi checkroot checkfs mountall mountnfs mountkernfs mountdevsubfs bootlogd bootlogs bootmisc skeleton rc rcS rc.local hwclock procps udev eudev mtab networking network-manager dbus ssh sshd supervisor supervisord"
echo "[inner] 通用服务自启动（遍历 /etc/init.d）..."
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

echo "[inner] 启动 sshd 端口 $PORT ..."
/usr/sbin/sshd -D -p "$PORT" -o PermitRootLogin=yes -o PasswordAuthentication=yes -o UsePAM=no -o ListenAddress=0.0.0.0 2>&1
echo "[inner] sshd 已退出 code=$?"
exec sleep infinity
INNER
# 把内层脚本里的 CODENAME / __APT_BASE__ 占位符替换成真实值
sed -i "s/CODENAME/$CODENAME/g" "$ROOTFS/run_ubuntu.sh"
case "$MARCH" in
  x86_64) APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu" ;;
  *) APT_BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" ;;
esac
sed -i "s#__APT_BASE__#$APT_BASE#g" "$ROOTFS/run_ubuntu.sh"
chmod 755 "$ROOTFS/run_ubuntu.sh"

# 挂载系统目录并后台拉起 chroot 容器（保持挂载供 sshd 使用）
mount_system
$CHROOT_BIN "$ROOTFS" /bin/bash /run_ubuntu.sh >> "$ROOT/ubuntu.log" 2>&1 &
echo $! > "$ROOT/run_ubuntu.pid"

# host 侧诊断
{
  echo "[host] uname -m: $(uname -m)"
  echo "[host] version: $VERSION / arch: $ARCH / codename: $CODENAME"
  echo "[host] getenforce: $(getenforce 2>/dev/null || echo N/A)"
  echo "[host] rootfs=$(test -d "$ROOTFS" && echo yes || echo NO)"
  echo "[host] rootfs /bin/bash=$(test -x "$ROOTFS/bin/bash" && echo yes || echo NO)"
  echo "[host] rootfs sshd=$(test -x "$ROOTFS/usr/sbin/sshd" && echo yes || echo NO)"

  # chroot 冒烟测试（限时 8s）
  $CHROOT_BIN "$ROOTFS" /bin/sh -c 'echo CHROOT_OK' > "$ROOT/smoke.log" 2>&1 &
  SMOKE_PID=$!
  SMOKE_DONE=0
  i=0
  while [ $i -lt 8 ]; do
    if grep -q CHROOT_OK "$ROOT/smoke.log" 2>/dev/null; then SMOKE_DONE=1; break; fi
    if ! kill -0 "$SMOKE_PID" 2>/dev/null; then break; fi
    sleep 1
    i=$((i+1))
  done
  kill "$SMOKE_PID" 2>/dev/null
  if [ "$SMOKE_DONE" = "1" ]; then
    echo "[host] chroot smoke test: OK"
  else
    echo "[host] chroot smoke test: FAILED ($(head -3 "$ROOT/smoke.log" 2>/dev/null | tr '\n' ' '))"
  fi

  i=0
  while [ $i -lt 30 ]; do
    if pgrep -x sshd >/dev/null 2>&1; then
      echo up > "$SSH_UP"
      break
    fi
    sleep 1
    i=$((i+1))
  done
  echo "[host] pgrep sshd: $(pgrep -x sshd 2>/dev/null | tr '\n' ' ' || echo none)"
  echo "[host] listen $PORT: $(ss -tlnp 2>/dev/null | grep ":$PORT " || echo none)"
} >> "$ROOT/ubuntu.log" 2>&1

if [ -f "$SSH_UP" ]; then
  progress 100 "Ubuntu 已启动（SSH 端口 $PORT，root 密码 $SSH_PASS）"
else
  progress 100 "Ubuntu 已启动，但 SSH 未就绪（请查看状态卡或 /data/local/ubuntu/ubuntu.log）"
fi
exit 0
