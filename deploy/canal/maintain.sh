#!/bin/sh
set -eu

usage() { printf '%s\n' '用法：maintain.sh check|repair /绝对路径/oj-system.jar [--confirm-maintenance]' >&2; exit 2; }
test "$#" -ge 2 || usage
mode=$1
jar=$2
case "$mode" in check|repair) ;; *) usage ;; esac
case "$jar" in /*) ;; *) usage ;; esac
test -f "$jar" || usage
if test "$mode" = repair; then
  test "$#" -eq 3 || usage
  test "${3:-}" = --confirm-maintenance || { printf '%s\n' 'repair 必须确认题目写入、旧 writer 和增量已暂停。' >&2; exit 2; }
else
  test "$#" -eq 2 || usage
fi
# 凭据仅从环境或既有 Nacos 配置读取，不在命令行拼接密码。
exec java -jar "$jar" --spring.main.web-application-type=none \
  --spring.cloud.nacos.discovery.enabled=false --canal.sync.enabled=false \
  "--canal.sync.mode=$mode" "--canal.sync.maintenance-confirmed=$([ "$mode" = repair ] && printf true || printf false)"
