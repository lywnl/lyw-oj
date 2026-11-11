# Design

## Context

XXL-JOB 2.4.0 已用于 oj-job；oj-system 不注册新执行器。Reconciler 由原单活动消费线程拥有。公共 TokenInterceptor 对空登录令牌放行，因此本接口须独立鉴权。

## Goals / Non-Goals

统一触发，真实回传，有界执行，保持现有增量/对账安全协议。默认关闭新功能。不加任务表、Redis 锁、MQ、自动补跑、自动重试或新对账线程，不承诺故障下恰好一次，不操作真实环境。

## Decisions

1. oj-api 定义 RemoteQuestionReconcileService、ReconcileResult 和专用配置/常量；oj-job 条件启用该 Feign 及 questionIndexReconcileHandler。固定 POST /internal/question-index/reconcile，无请求体。Result 数据为 differences/repaired/durationMillis；只有有效成功响应才向 XxlJobHelper 报成功。日志仅写受控原因与统计，不输出原始远程异常、响应或配置。
2. `question.reconcile` 配置：enabled=false，internal-token/target-url 无默认值，timeout-millis=600000，connect-timeout-millis=3000，read-timeout-millis=660000。启用服务端校验令牌（部署随机生成至少 32 字符）和正超时；客户端另验证明确 http(s) 地址，无用户信息、查询、片段，读取时限大于总预算。关闭 Feign 重试、重定向和请求日志，配置仅限该客户端；绝不能因空地址退化为服务发现。只使用启动配置，修改超时须同步核对 Admin 时限。
3. 系统入口在 MVC 前以专用 X-Question-Reconcile-Token 做常量时间校验。缺失/错误 403；关闭、非 normal 或同步非 RUNNING 503；占用 409；超时 504；执行错误 500。沿用 Result，不更改其他接口。网关在公开转发前拒绝此入口及服务前缀/编码/分号变体，不能由白名单或用户令牌绕过。
4. Runner 以单槽和 Future 交接，接收时用 System.nanoTime 开始总预算。HTTP 仅等待，原消费线程完成增量 ACK 后推进对账。请求取消只置标记，写 ES 前和阶段边界检查，不 interrupt 整个消费线程；对账取消产生的 InterruptedException 在本轮内收口，不冒泡成增量线程退出。
5. 清理后才完成 Future 和释放槽。候选超时/空库保护报告失败；连接异常、BLOCKED、停机结束待执行及当前 Future，增量可按原策略重连，但不重启本轮。清理异常也必须结束 Future，保持隔离并阻塞同步，不能释放给下一轮；PIT 自动过期仅作兜底。HTTP 先断开时仍受服务端截止约束。
6. 删除 nextReconcile、本地 Cron 和间隔。旧 reconcile-interval-millis 仅作 normal 同步启动迁移检查，任何非空值含 0 均拒绝，维护模式保留。新功能关闭不关增量，维护进程不开 HTTP/执行器。
7. Admin 参数：Cron 0 0 2 * * ?，JVM Asia/Shanghai，FIRST、DISCARD_LATER、DO_NOTHING、失败重试 0、执行上限 720 秒；服务端默认 600 秒，Feign 660 秒。停机不补历史，人工触发同样走权限/单槽。

## Risks / Trade-offs

每日同步 HTTP 占一个线程和连接，由有界时限限制，避免新增结果查询及任务库。单槽不跨进程，唯一活动消费者为部署前提。丢响应可能使平台失败而后台已结束，不自动重试，人工核查后可再次幂等对账。合作取消可能等待当前有界 I/O 返回；超时响应不表示清理完成。成功表示按原协议完成一轮，不保证瞬时跨存储一致。

## Validation

JDK 17、JUnit 实际 Reconciler 配接口替身覆盖单槽、取消、重连、停止、超时及变化候选。MockMvc/真实 HTTP 验证鉴权、配置与状态，网关覆盖普通/管理员/编码路径。Docker 运行真实 2.4.0 Admin、独立 MySQL 和现有 XxlJobSpringExecutor，经新 Handler/入口调用真实 Runner，数据源使用可控替身；用 Admin 记录验证成功/失败回调，明确不等于真实 MySQL/Canal/ES 同步验证。

## Migration Plan

先停新任务，配两端开关/令牌/目标/时限，移除旧间隔；发布系统及任务入口，确认唯一消费者。核对 Admin 时区及下次触发，手动验证再启用每日任务。回退先停任务、等待槽释放，再回退代码配置。本轮只交付说明，不修改真实配置；按基线清理测试资源并保留旧证据及报告。
