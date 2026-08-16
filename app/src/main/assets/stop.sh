#!/system/bin/sh
# Ubuntu 控制器 —— 停止 Ubuntu（精确杀掉本应用启动的容器进程，卸载挂载）
ROOT=/data/local/ubuntu
PIDFILE="$ROOT/run_ubuntu.pid"

# 1. 通过 pidfile 停止 run_ubuntu.sh 进程树
if [ -f "$PIDFILE" ]; then
  PID=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$PID" ] && [ "$PID" -gt 0 ] 2>/dev/null; then
    # 先尝试优雅终止子进程与主进程
    pkill -P "$PID" 2>/dev/null || true
    kill "$PID" 2>/dev/null || true
    sleep 1
    # 强制终止
    kill -9 "$PID" 2>/dev/null || true
    pkill -9 -P "$PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
fi

rm -f "$ROOT/.ssh_up"

# 2. 兜底：杀死所有 chroot root 指向 $ROOT/rootfs.* 的进程（避免误杀宿主 sshd）
for p in /proc/[0-9]*; do
  [ -d "$p/root" ] || continue
  rootlink=$(readlink "$p/root" 2>/dev/null || true)
  case "$rootlink" in
    "$ROOT"/rootfs.*)
      pid=$(basename "$p")
      kill -9 "$pid" 2>/dev/null || true
      ;;
  esac
done

# 3. 卸载所有 rootfs 下的挂载点（先深层后上层）
mounts=$(awk -v root="$ROOT" 'index($2, root "/rootfs.") == 1 {print $2}' /proc/mounts 2>/dev/null | sort -r)
for m in $mounts; do
  umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true
done

echo "Ubuntu 已停止"
exit 0
