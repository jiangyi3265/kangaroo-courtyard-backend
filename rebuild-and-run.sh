#!/usr/bin/env bash
# 重新构建并启动后端。
#
# 注意：必须先删掉 ruoyi-admin.jar 再 package。
# spring-boot:repackage 只在 jar 插件重新产出 artifact 时才会重新打包，
# 而 ruoyi-admin 模块自身没有源码变更时 jar 插件会跳过，
# 结果就是 fat jar 里仍然嵌着上一次的 ruoyi-kangaroo-*.jar，
# 业务代码改了却"看起来没生效"。
set -e
cd "$(dirname "$0")"

PID=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":8080" | head -1 | awk '{print $NF}' || true)
if [ -n "$PID" ]; then
  echo "停止占用 8080 的进程 $PID"
  taskkill //F //PID "$PID" >/dev/null 2>&1 || true
  sleep 3
fi

rm -f ruoyi-admin/target/ruoyi-admin.jar ruoyi-admin/target/ruoyi-admin.jar.original
mvn -o -q -DskipTests package

echo "构建完成: $(ls -la ruoyi-admin/target/ruoyi-admin.jar)"

# 本地联调用 -Dkangaroo.wx.mock-enabled=true；生产环境绝不要带这个参数
java "$@" -jar ruoyi-admin/target/ruoyi-admin.jar
