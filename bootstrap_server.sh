#!/bin/bash
# ============================================================
# 生产化一键部署脚本（在 Ubuntu chroot 内以 root 运行）
# 作用：无 systemd 环境下，用 supervisor 统一管理 Web 服务栈
#   栈：supervisor + nginx + php-fpm + mariadb + redis + 常用工具
# 适用：22.04 / 24.04 / 26.04，amd64 / arm64 / armhf
# 用法：bash /root/bootstrap_server.sh
# 说明：可重复运行（幂等），已装的服务会跳过。
# ============================================================
set -e
umask 022
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true

log(){ echo "[bootstrap] $*"; }

# ---------- 0. 阻止 apt 在 chroot 里调用 systemd 启停服务 ----------
# chroot 无 systemd，postinst 里的 invoke-rc.d 会卡住/报错；exit 101 = 不启动不停止
if [ ! -x /usr/sbin/policy-rc.d ]; then
  cat > /usr/sbin/policy-rc.d <<'EOF'
#!/bin/sh
exit 101
EOF
  chmod 755 /usr/sbin/policy-rc.d
fi

# ---------- 1. 基础工具 + 服务栈 ----------
log "更新软件源..."
apt-get update -y -qq

log "安装基础工具与服务栈（可能需要几分钟）..."
apt-get install -y --no-install-recommends \
    curl wget git vim htop nano ca-certificates sudo unzip \
    iproute2 net-tools sysvinit-utils \
    supervisor nginx \
    php-fpm php-cli php-mysql php-curl php-gd php-mbstring php-xml php-zip php-intl \
    mariadb-server redis-server

# ---------- 2. 运行目录（chroot 无 systemd，需手动建；Android umask=000 需强制权限） ----------
log "创建运行目录并修正权限..."
mkdir -p /run/mysqld /run/php /run/nginx
mkdir -p /var/log/nginx /var/log/php /var/log/mysql /var/log/redis /var/log/supervisor
chown mysql:mysql /run/mysqld 2>/dev/null || true
chmod 755 /run /run/mysqld /run/php /run/nginx 2>/dev/null || true

# ---------- 3. 检测版本相关路径（3 个 Ubuntu 版本的 php-fpm / mariadb 路径不同） ----------
PHP_FPM_BIN=$(ls /usr/sbin/php-fpm* 2>/dev/null | head -1)
PHP_VER=$(php -r 'echo PHP_MAJOR_VERSION.".".PHP_MINOR_VERSION;' 2>/dev/null)
PHP_FPM_CONF="/etc/php/${PHP_VER}/fpm/php-fpm.conf"
MYSQLD_BIN=$(ls /usr/sbin/mariadbd /usr/sbin/mysqld 2>/dev/null | head -1)
log "PHP-FPM=$PHP_FPM_BIN (${PHP_VER})   MariaDB=$MYSQLD_BIN"

# ---------- 4. MariaDB 数据目录初始化（若首次安装未初始化） ----------
if [ ! -d /var/lib/mysql/mysql ]; then
  log "初始化 MariaDB 数据目录..."
  mariadb-install-db --user=mysql --datadir=/var/lib/mysql >/dev/null 2>&1 || \
    mysql_install_db --user=mysql --datadir=/var/lib/mysql >/dev/null 2>&1 || true
fi
chown -R mysql:mysql /var/lib/mysql 2>/dev/null || true

# ---------- 5. 生成 supervisor 服务配置 ----------
log "写入 supervisor 服务配置..."

cat > /etc/supervisor/conf.d/nginx.conf <<'EOF'
[program:nginx]
command=/usr/sbin/nginx -g "daemon off;"
autostart=true
autorestart=true
startretries=10
stdout_logfile=/var/log/nginx/supervisor.log
redirect_stderr=true
EOF

cat > /etc/supervisor/conf.d/php-fpm.conf <<EOF
[program:php-fpm]
command=$PHP_FPM_BIN --nodaemonize --fpm-config $PHP_FPM_CONF
autostart=true
autorestart=true
startretries=10
stdout_logfile=/var/log/php/supervisor.log
redirect_stderr=true
EOF

cat > /etc/supervisor/conf.d/mariadb.conf <<EOF
[program:mariadb]
command=$MYSQLD_BIN --user=mysql
autostart=true
autorestart=true
startretries=10
stdout_logfile=/var/log/mysql/supervisor.log
redirect_stderr=true
EOF

cat > /etc/supervisor/conf.d/redis.conf <<'EOF'
[program:redis]
command=/usr/bin/redis-server --daemonize no
autostart=true
autorestart=true
startretries=10
stdout_logfile=/var/log/redis/supervisor.log
redirect_stderr=true
EOF

# ---------- 6. 启动 supervisor 并加载全部服务 ----------
log "启动 supervisor 并加载服务..."
pkill supervisord 2>/dev/null || true
sleep 1
/usr/bin/supervisord -c /etc/supervisor/supervisord.conf
sleep 3
supervisorctl -c /etc/supervisor/supervisord.conf status

# ---------- 7. nginx 默认静态站点 ----------
log "配置 nginx 默认站点..."
mkdir -p /var/www/html
cat > /var/www/html/index.html <<'EOF'
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>TVUbuntu</title>
  <style>
    body { font-family: sans-serif; background: #1A1A2E; color: #E8E8E8; text-align: center; padding-top: 10vh; }
    h1 { color: #00C897; }
    p { color: #8899AA; }
  </style>
</head>
<body>
  <h1>TVUbuntu Web Stack is running</h1>
  <p>nginx · PHP-FPM · MariaDB · Redis</p>
</body>
</html>
EOF

cat > /etc/nginx/sites-available/default <<'EOF'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    root /var/www/html;
    index index.html;
    server_name _;

    location / {
        try_files $uri $uri/ =404;
    }
}
EOF

if [ -d /etc/nginx/sites-enabled ]; then
    rm -f /etc/nginx/sites-enabled/default
    ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default
fi

nginx -s reload 2>/dev/null || true

log "==================== 完成 ===================="
log "服务端口：nginx=80  mariadb=3306  redis=6379  ssh=22"
log "常用命令："
log "  supervisorctl status              # 查看所有服务状态"
log "  supervisorctl restart nginx       # 重启单个服务"
log "  supervisorctl tail -f nginx       # 跟踪 nginx 日志"
log "  mysql -u root                     # 以 root 登录 MariaDB（unix_socket 认证）"
log "=============================================="
