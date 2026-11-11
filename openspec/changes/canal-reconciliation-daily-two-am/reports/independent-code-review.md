# 独立代码质量评审（最终复核）

- 风险等级：strict。
- 结论：PASS。R1、R2、R3 及后续 R4 均已关闭，当前检查范围内无未解决的阻塞问题。
- 评审者：`/root/xxl_reconciliation_review`，未撰写业务实现；独立阅读代码、测试与运行证据，并在项目 `.codex` 执行无网络框架探针。
- 复核时间：2026-11-11T11:46:43.982219+00:00。
- 当前基线：`.codex/canal-reconciliation-xxl-job/20261111-a1/evidence/independent-code-final-baseline.json`。a6 构建快照的 359 个文件与当前源码逐项哈希一致；部署说明及脚本另行记录哈希。
- 历史：初轮及 a4 中间评审保存在 `reports/history/independent-code-review-a2-a4.md`，保留发现和失败复现，不改写为一次通过。

## 发现关闭情况

| 编号 | 问题及修复 | 本轮复核依据 |
| --- | --- | --- |
| R1 / P2 | 错误 URL 在 Feign Registrar 阶段回显凭据。新增 ValidatedReconcileClientCondition，在客户端元数据解析前校验并以固定消息拒绝；保留构造器校验。 | QuestionReconcileConfigurationTest 的完整 Spring 上下文异常链测试通过；独立无网络探针重跑由凭据泄漏 true 变为 false。 |
| R2 / P2 | 首次 PIT 未交给 Reconciler 时清理失败无法隔离。IndexStore 抛出 CursorCleanupException，Runner 置 cleanupFailed/BLOCKED，结束 Future 并保留槽。 | ElasticsearchIndexStoreTest 的首次搜索/删除同时失败用例和 ReconciliationRequestFlowTest 的首次游标隔离用例通过；清理失败后不接受新轮次。 |
| R3 / P2 | 分号参数内编码斜线使网关和 Tomcat 规范化不同。公共识别器同时检查原始参数剥离前后两条解码路径。 | 网关与 MockMvc 矩阵参数回归通过；独立 Tomcat/Spring 无网络探针对原绕过输入显示 GATEWAY_BLOCKED=true。 |
| R4 / P2 | 已关闭 PIT 的 404 被误判为清理故障，暂时搜索失败可错误阻塞增量。closeCursor 只接受 DELETE /_pit 的明确 404 + Boolean true succeeded + 整数 0 num_freed，其他 404 仍失败。 | repeatedCloseAndExplicitlyMissingPitAreSuccessful、arbitrary404IsNotTreatedAsSuccessfulCleanup、searchFailureCanCloseAgainDuringRunnerCleanupAndThenRecover 全部通过；最后一项使用真实 HTTP ES 替身与实际 Runner，验证可恢复。 |

Handler 已采用安全的 HTTP 状态日志区分内部调用失败，未输出远端正文、URL 或凭据。维护说明已经改为 XXL-JOB 启停和旧参数迁移，不再描述本地 Cron 为当前实现。

## 代码边界复核

- 功能默认关闭、显式唯一消费者地址、专用令牌以及客户端/服务端时限校验均保留。普通用户令牌不替代内部凭据，网关不能通过登录身份或路径变体放行内部入口。
- Feign 专属配置关闭重试与重定向，NoOpLogger 防止请求头日志；空 POST 的固定长度流式方式防止底层 EOF 重发。配置不影响其他客户端或既有竞赛 Handler。
- 请求接收有同步保护，单槽覆盖等待、执行和清理。Future 不给调用者完成权限；取消使用标记，实际 Reconciler 仍由消费线程独占，清理成功前不释放槽。
- 服务端以单调时钟控制总预算；取消本轮不退出增量线程。连接异常结束本轮而非自动重启；阻塞、停机及清理失败均结束 Future。真实停机和合作取消有不同分支。
- ACK 仍位于增量写入成功之后；位点屏障、变化候选排除、空库保护及手动维护保持。完成结果表示按原协议结束一轮，不承诺瞬时跨存储一致或故障下恰好一次。

## 实际证据

1. `.codex/canal-question-es-sync/evidence/xxl-20261111-e2e-a6.log` 与其 JUnit XML：15 个测试类合计 **89 项，0 失败、0 错误、0 跳过**；oj-job 11、oj-system 75、网关 3。构建使用 Maven JDK 17 镜像，BUILD SUCCESS。
2. `.codex/canal-reconciliation-xxl-job/20261111-e2e-a6/evidence/result.json`：真实 XXL-JOB 2.4.0 Admin、执行器、Feign、系统 HTTP 与 Runner 串联。已核对实际成功统计、执行中不提前成功、内部 409、平台重叠丢弃、缩短预算后的 504 失败、存储故障失败及恢复后成功，以及后续五次 Cron 均为北京时间 02:00。
3. a6 两个应用运行日志及 Admin 日志已读取；同一消费线程承担对账读写，Admin JVM 明确 Asia/Shanghai。对账 Reader/Index/Canal 是测试替身，不能据此声明真实 MySQL→Canal→ES 同步通过。
4. 独立修复探针：`.codex/canal-reconciliation-xxl-job/20261111-a1/review-probes/final-probe-result.json` 及 final-probe-1/2.log。只在宿主 JDK 25 用当前已构建产物运行无网络 Spring/Tomcat 类验证，未启动服务，不代替 a6 的 JDK 17 验收。
5. ES 8.5.3 PIT 幂等语义依据保存在 review-probes/pit-close-official-source.json，含官方 ClosePointInTimeResponse/ClearScrollResponse 来源与哈希。原缺陷探针和中间失败日志保留。

## 测试隔离与失败记录复核

初版探针仅关闭 bootstrap，SpringApplication 的其他监听器仍可能初始化默认 Nacos；此前实际运行发现该连接尝试失败，不能把那一轮称为完全隔离通过。系统探针现改为纯 AnnotationConfigServletWebServerApplicationContext，只导入必要配置；脚本另显式关闭 Nacos，并使用经 inspect 确认的 --internal Docker 网络。a6 的 oj-system/oj-job 运行日志均无 nacos.client 初始化痕迹，无 main 线程未处理异常。

SQL 客户端显式 utf8mb4，真实回调统计能够正确读取中文。控制模式文件交接的 NoSuchFileException 竞态现只保留旧模式并下轮重读，不再退出整个测试 Web 上下文；a6 完整链路通过。此前字符集断言、脚本问题、默认 Nacos 尝试及文件交接失败日志应继续在最终交付报告中如实列出。

a6 result.json 的 cleanup 残留列表为空；独立只读 docker inspect 确认本轮 5 个容器和网络均不存在。共享 Maven 缓存按计划保留；其他轮次及全局本轮资源由主执行者在交付报告汇总，本文不扩大清理结论。

## 限制与交接

此次 PASS 是当前范围的独立代码质量结论，支持主执行者完成正式 verify；本报告未运行 record-verify/check-verify，也不自动代表归档、提交或发布。真实业务 Nacos、Admin 任务启用、数据库/ES 数据及线上容量均未作为通过结论；上线仍须按文档确认唯一消费者、令牌、时限、时区与任务配置。

没有未解决的代码阻塞项。正式证据引用的日志、探针及本报告保留至用户明确要求删除；不要把测试历史失败改写为从未发生。
