# 独立规划评审

## 评审结论

**PASS**

当前 strict 规划已补齐此前阻塞的行为决策，proposal、spec、design 和 tasks 之间一致，可以作为实施基线交给 `openspec-apply-change`。本报告仅评审规划，不代表实现或真实集成验收通过。

## 评审基线

- 变更：`threadpool-style-container-pool`
- 风险等级：`.openspec.yaml` 登记为 `strict`
- 已读产物：`proposal.md`、`specs/judge/container-pool/spec.md`、`design.md`、`tasks.md`
- 已读规则：`.agents/shared/openspec/workflow-policy.md`
- 评审范围：规划语义、产物依赖、任务可实施性和验收可观察性。

## 已核实的一致性

1. 任务调度统一为有界 `JudgeTask` 队列和一容器一 `ContainerWorker`；请求线程不再直接竞争执行中的容器。
2. 扩容顺序、最大资源数、临时 Worker 空闲回收和核心资源常驻在规格、设计与任务中一致。
3. 满载拒绝条件明确为队列已满、无空闲资源、达到最大容器数且无法创建容器；同步提交返回 `SYSTEM_BUSY`，不进入执行队列。
4. 异步池满行为已收敛：`JudgeConsumer` 使用 `basicNack(requeue=false)` 交给死信流程，`DeadLetterConsumer` 负责把 Redis 状态收敛为失败，不在池层维护第二套重试计数。
5. 核心容器初始创建或启动失败时，判题服务启动失败并保持未就绪；运行期核心容器故障隔离后补建。对应场景和任务 2.4 已登记。
6. 用户代码判题失败与 Docker/容器故障分离处理，代码清理、容器复用和失效重建均有可观察场景。
7. tasks.md 依赖顺序合理，覆盖模型配置、池生命周期、执行链路、RabbitMQ 可靠性以及并发和集成验证；未发现循环依赖或缺少必需产物。

## 实施阶段提醒（非阻塞）

- `tasks.md` 5.2/5.3 要求真实 RabbitMQ、Redis、数据库、Docker 和构建验证；实际启用外部环境或操作真实数据前仍须按项目规则取得具体授权，并记录真实限制。
- `design.md` 仅要求 prefetch 与本地容量匹配，实施时应给出明确配置值或计算关系，并验证未确认消息不会无限积压。
- 队列满后临时扩容与 Worker 同时消费存在竞态，2.2 和 5.1 需要包含并发提交测试，验证最大数量和计数回滚，而非仅做串行边界测试。
- 实施前应通过调用关系确认 `SandboxServiceImpl` 是否仍在主链路；若不在范围内，保持不改动并在实施证据中说明。

## 结论依据

上述提醒不改变已确认的需求、接口语义或验收标准，也不阻塞按当前 tasks.md 开始实施。若实施中发现这些事项改变范围或行为，应回到 continue/review 流程修订规划。
