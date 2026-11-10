#!/bin/sh
set -eu

base=/home/admin/canal-server
runtime=/tmp/oj-canal-conf
test -s /config/instance.properties || { printf '%s\n' '缺少显式实例配置，请先生成配置并核对起点。' >&2; exit 1; }
test -s /config/canal-overrides.properties || exit 1
mkdir -p "$runtime" /state/question-sync
cp -R "$base/conf/." "$runtime/"
cat /config/canal-overrides.properties >> "$runtime/canal.properties"
mkdir -p "$runtime/question-sync"
cp /config/instance.properties "$runtime/question-sync/instance.properties"

# 固定 Canal 1.1.8 的空事务阈值，保留用于对账屏障的源事务末尾。
awk '
  { print }
  /name="filterTransactionEntry"/ {
    print "        <property name=\"filterEmtryTransactionEntry\" value=\"true\" />"
    print "        <property name=\"emptyTransctionThresold\" value=\"0\" />"
    patched=1
  }
  END { if (!patched) exit 1 }
' "$runtime/spring/file-instance.xml" > "$runtime/spring/file-instance.new"
mv "$runtime/spring/file-instance.new" "$runtime/spring/file-instance.xml"

# 1.1.8 的注册副本 INFO 日志包含 reportPasswd，禁止随 root 级别升到 INFO/DEBUG。
awk '
  /<\/configuration>/ { print "    <logger name=\"com.alibaba.otter.canal.parse.inbound.mysql.MysqlConnection\" level=\"WARN\"/>" }
  { print }
' "$runtime/logback.xml" > "$runtime/logback.new"
mv "$runtime/logback.new" "$runtime/logback.xml"

heap=${CANAL_HEAP:-256m}
case "$heap" in *[!0-9mMgG]*|'') printf '%s\n' 'CANAL_HEAP 无效' >&2; exit 1 ;; esac
java -Xms128m "-Xmx$heap" -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true \
  "-Dcanal.conf=$runtime/canal.properties" "-Dcanal.conf.dir=$runtime" \
  "-Dlogback.configurationFile=$runtime/logback.xml" \
  -cp "$runtime:$base/lib/*" com.alibaba.otter.canal.deployer.CanalLauncher &
java_pid=$!
(
  while kill -0 "$java_pid" 2>/dev/null; do
    if test -f /state/question-sync/meta.dat && grep -q '"cursor"[[:space:]]*:[[:space:]]*{' /state/question-sync/meta.dat; then
      touch /state/metadata-established
    fi
    sleep 1
  done
) &
watch_pid=$!
stop() {
  trap - INT TERM EXIT
  kill "$java_pid" 2>/dev/null || true
  wait "$java_pid" 2>/dev/null || true
  kill "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
}
trap stop INT TERM EXIT
wait "$java_pid"
