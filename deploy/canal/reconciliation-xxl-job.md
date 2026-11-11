# XXL-JOB 题目对账维护说明

由现有调度中心触发 `oj-job` 的 `questionIndexReconcileHandler`，通过专用内部 HTTP 请求交给唯一活动 `oj-system` 消费实例。对账仍在 `question-es-sync` 线程分页执行，正常完成后才回传成功。

## 配置与启用

两个服务均通过现有 Nacos 或环境变量配置 `question.reconcile`，按启动配置使用。示例仅说明键名，开关默认关闭，真实凭据不能提交到仓库：

```yaml
question:
  reconcile:
    enabled: false
    internal-token: ${QUESTION_RECONCILE_INTERNAL_TOKEN:}
    timeout-millis: 600000
    # 以下两项连接参数及目标由 oj-job 使用。
    target-url: ${QUESTION_RECONCILE_TARGET_URL:}
    connect-timeout-millis: 3000
    read-timeout-millis: 660000
```

内部令牌随机生成至少 32 位非空白 ASCII 字符，两端一致；不得复用 `xxl.job.accessToken`、用户 JWT 或数据库密码。`target-url` 必须为明确的 HTTP(S) 地址，指向唯一开启 Canal 消费的实例，不使用包含其他禁用副本的负载均衡地址，不携带账号、查询参数或片段。只在受控内部网络访问，网关始终禁止此接口。

开启时会校验配置；错误不会退化为服务发现或免鉴权。运行中不热切换，按下列顺序变更：

1. 新任务先保持停用，确认同时只有一个活动 Canal 消费者。
2. 从 Nacos、配置文件及环境变量中移除 `canal.sync.reconcile-interval-millis` / `CANAL_SYNC_RECONCILE_INTERVAL_MILLIS`，含旧值 `0`；新版 normal 同步遇到这些配置会明确拒绝启动。
3. 配置两端专用令牌、开关和 oj-job 的明确目标，部署系统服务及任务入口；确认同步为 RUNNING。
4. 在现有 XXL-JOB 执行器分组新建下表任务，先“执行一次”，检查实际结束及差异/修复/耗时，再启用定时调度。

## XXL-JOB 参数

| 项目 | 设置 |
| --- | --- |
| 执行器 | 已有 oj-job 执行器分组 |
| 运行模式 / JobHandler | BEAN / `questionIndexReconcileHandler` |
| Cron | `0 0 2 * * ?` |
| 路由 / 阻塞策略 | 第一个（FIRST）/ 丢弃后续调度（DISCARD_LATER） |
| 调度过期策略 | 忽略（DO_NOTHING） |
| 失败重试次数 / 子任务 | 0 / 留空 |
| 任务超时 | 720 秒 |
| 调度中心 JVM 时区 | Asia/Shanghai |

XXL-JOB 2.4.0 Cron 使用调度中心 JVM 默认时区；数据库 JDBC 的 `serverTimezone` 不能替代它。仓库 Compose 为 Admin 声明 `TZ` 与 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai`；已有容器需要发布时重新创建并核对，不是修改文件后立即生效。控制台检查下次执行时间确实为北京时间 02:00，不把 Spring 的 `0 0 2 * * *` 原样用于此版本 Admin。

平台状态成功必须包含实际对账统计；“调度成功/已发送”不等于“执行成功”。停机错过的历史时间不会补跑。代码不自动注册或启用真实任务。

## 失败、超时与关闭

内部调用为无请求体的 `POST /internal/question-index/reconcile`，凭据只通过 `X-Question-Reconcile-Token` 发送。响应保持 `code/msg/data`。拒绝和异常时 data 为 null：鉴权失败 403、忙碌 409、未启用/同步不可用 503、超时 504、执行或保护失败 500；普通业务接口状态不变。

服务端默认总预算 600 秒，Feign 连接 3 秒、读取 660 秒，平台 720 秒。修改时保持服务端总预算小于读取等待，且平台更长；两端读取同一套预算设置，平台同步调整。单页候选等待 300 秒保持原规则。

同一时间只接收一轮，包括等待和清理阶段。超时/中断只取消本轮，消费线程在安全边界结束和清理，不中断增量线程。上游丢失响应或手动终止平台等待不等于后台已经停下；服务端预算继续生效，清理前新请求仍被拒绝。合作取消可能等待当前有界 I/O 返回。

临时连接故障结束当前对账，增量继续按原策略重连，不自动重启本轮。位点候选超时或空库保护在平台显示失败，按既有 check/repair 流程处理。游标清理失败使同步器保持 BLOCKED 和隔离，不以“等待结束”冒充已清理，需先检查原因和残留，不能直接启动第二个消费者。PIT 自动过期只作资源兜底。

网络丢失响应时后台可能已经完成；先查同步状态及平台日志，再决定人工重试。重复执行按现有幂等修复协议处理，不承诺故障下恰好一次。

关闭每日对账只需停用 XXL-JOB 任务；若要关闭内部触发，排空当前请求后修改 `question.reconcile.enabled=false` 并重启对应服务，不会关闭 Canal 增量和既有竞赛任务。维护 check/repair 仍用原独立非 Web 进程。

## 回退及验证

回退前先停用任务，确认本轮结束、同步线程及请求槽无残留，再回退应用和对应配置；旧版可能重新启用本地 Cron，严禁同时保留平台触发。

单元/HTTP 验证采用项目 JUnit、Mockito 和 MockMvc；真实调度验收脚本为 `deploy/canal/tests/xxl_reconciliation.py`，使用独立 Admin/MySQL、真实执行器/Handler/HTTP/Runner，对账读写为内存替身。它不连接业务 MySQL、Canal、ES 或 Nacos，也不能代替真实数据同步验收。脚本报告记录命令、镜像、执行结果及资源清理；报告保留至用户明确要求删除。
