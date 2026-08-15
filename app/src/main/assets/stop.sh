#!/system/bin/sh
# Ubuntu 控制器 —— 停止 Ubuntu（杀掉 sshd 与容器进程，卸载挂载）
ROOT=/data/local/ubuntu

# 杀掉 sshd（前台 -D 运行）与容器内 sleep（run_ubuntu.sh 最后 exec sleep infinity）
pkill -x sshd 2>/dev/null
pkill -f "run_ubuntu.sh" 2>/dev/null
pkill -f "sleep infinity" 2>/dev/null
rm -f "$ROOT/.ssh_up"

# 卸载所有 rootfs 下的挂载点（hostlog/dev/pts/dev/sys/proc）
for d in "$ROOT"/rootfs.*; do
  [ -d "$d" ] || continue
  umount "$d/hostlog" 2>/dev/null
  umount "$d/dev/pts" 2>/dev/null
  umount "$d/dev" 2>/dev/null
  umount "$d/sys" 2>/dev/null
  umount "$d/proc" 2>/dev/null
done

echo "Ubuntu 已停止"
exit 0
