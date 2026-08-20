#!/system/bin/sh
# TVUbuntu Proot 模式停止脚本
# 不调用 umount，只杀 proot 进程树并清理标记
ROOT=/data/data/com.ubuntucontroller/files/ubuntu
PIDFILE="$ROOT/run_ubuntu.pid"

# 1. 通过 pidfile 停止 run_ubuntu_proot.sh 进程树
if [ -f "$PIDFILE" ]; then
  PID=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$PID" ] && [ "$PID" -gt 0 ] 2>/dev/null; then
    pkill -P "$PID" 2>/dev/null || true
    kill "$PID" 2>/dev/null || true
    sleep 1
    kill -9 "$PID" 2>/dev/null || true
    pkill -9 -P "$PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
fi

# 2. 兜底：按 rootfs 路径精确杀死 proot 主进程（其命令行含 rootfs.<版本>.<架构>），
#    再显式清理容器内 dropbear 与 run_ubuntu_proot.sh 进程，并释放启动锁。
#    旧兜底条件 *rootfs.* + *run_ubuntu_proot.sh* 无进程能同时命中（proot 进程 cmdline 只含
#    rootfs 路径，容器 bash 只含脚本名），导致 PIDFILE 丢失时停止失效、端口残留。
pkill -9 -f "rootfs\." 2>/dev/null || true
pkill -f "start_proot.sh" 2>/dev/null || true
pkill -x dropbear 2>/dev/null || true
pkill -f "run_ubuntu_proot.sh" 2>/dev/null || true
rm -rf "$ROOT/.start_lock"

# 3. 清理状态标记
rm -f "$ROOT/.ssh_up"

echo "Ubuntu 已停止"
exit 0
