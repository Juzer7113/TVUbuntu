#!/system/bin/sh
# ============================================================================
# bake_adb_keys.sh —— 将 TVUbuntu 固定公钥烘焙进固件，实现「经典网络 ADB 零弹窗」
#
# 适用场景：Android 5–10 盒子（无「无线调试」配对码功能）或任何不想走无障碍自动点击的盒子。
# 原理：把 App 内置固定公钥（assets/adb_key.pub，adb_keys 格式）追加到设备 adb_keys
#       白名单，adbd 见到本 App 的 RSA 签名即直接信任，不再弹「允许 USB 调试」。
#
# 用法（在已 root 的盒子上，或打包进固件 post-install）：
#   sh bake_adb_keys.sh
#
# 说明：
#   - 同时写 /data/misc/adb/adb_keys 与 /adb_keys（两套路径不同 ROM 取其一）。
#   - 写后修正属主/权限/SELinux 上下文（adbd 以 shell 域读取）。
#   - 如需关闭认证（极不推荐，任何 adb 都能连），改 build.prop ro.adb.secure=0。
# ============================================================================

PUB_FILE="${1:-/system/etc/tvubuntu_adb.pub}"

if [ ! -f "$PUB_FILE" ]; then
    echo "错误：公钥文件不存在: $PUB_FILE"
    echo "请先执行: python gen_adb_pubkey.py ../app/src/main/assets/adb_key.pem $PUB_FILE"
    exit 1
fi

KEY=$(cat "$PUB_FILE")

for TARGET in /data/misc/adb/adb_keys /adb_keys; do
    DIR=$(dirname "$TARGET")
    mkdir -p "$DIR" 2>/dev/null
    # 去重追加
    if [ -f "$TARGET" ] && grep -qF "$KEY" "$TARGET"; then
        echo "已存在，跳过: $TARGET"
    else
        echo "$KEY" >> "$TARGET"
        echo "已写入: $TARGET"
    fi
    # 权限/属主/SELinux（不同 ROM 可能无 restorecon，忽略错误）
    chown system:shell "$TARGET" 2>/dev/null
    chmod 640 "$TARGET" 2>/dev/null
    chcon u:object_r:adb_keys_file:s0 "$TARGET" 2>/dev/null
    restorecon "$TARGET" 2>/dev/null
done

# 重启 adbd 使白名单生效
setprop ctl.restart adbd 2>/dev/null
echo "完成。建议重启盒子后验证：TVUbuntu 点 ADB 应直接变绿（无弹窗）。"
