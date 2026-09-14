# 规划评审

- 风险等级：strict
- 结论：PASS
- 评审者与独立性：当前执行上下文完成聚合评审；`reviews/independent-review.md` 由独立评审上下文完成并复审通过。
- 基线：当前 `proposal.md`、`specs/judge/container-pool/spec.md`、`design.md`、`tasks.md`、`.openspec.yaml` 及项目 `AGENTS.md`、OpenSpec 规则。

## 阻塞项

无。异步池满时统一由 `JudgeConsumer` 执行 `basicNack(requeue=false)` 并交给 `DeadLetterConsumer` 收敛 Redis；核心容器初始创建或启动失败时判题服务启动失败，已在规格、设计和任务中保持一致。

## 警告及处置

- 真实 RabbitMQ、Redis、数据库和 Docker 集成验证需要对应环境与授权，实施阶段未获授权前只执行本地替身和静态验证。
- RabbitMQ prefetch 与本地队列容量的具体数值关系在实施任务中确定并验证，不改变已确认行为。
- 扩容竞态、池满并发竞态和核心故障补建必须通过并发测试覆盖。
- `SandboxServiceImpl` 是否仍被调用需在实施前通过调用关系核实；本次设计不扩大到未使用的旧执行路径。

## 实施交接

- 实现范围：`oj-modules/oj-judge` 容器池、判题执行和 RabbitMQ 消费链路，必要时调整 `oj-friend` 的提交适配。
- 依赖顺序：任务模型与配置 → Worker/池生命周期 → 判题执行 → RabbitMQ 可靠性 → 并发与集成验证。
- 必需验证：任务唯一消费、核心/临时扩缩容、`SYSTEM_BUSY` 拒绝、容器故障补建、用户代码清理、异步 ACK/死信和 Maven 构建。
- 环境边界：真实中间件和数据库操作不在本次默认授权内；未授权项目保留 `ENVIRONMENT_NOTE`，不以替身结果代替真实验收。
