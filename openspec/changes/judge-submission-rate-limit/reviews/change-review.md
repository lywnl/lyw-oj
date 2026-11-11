# 当前规划评审：全局每秒 3 次

- 风险等级：strict；核验方式：machine；结论：PASS。
- 最新来源：.codex/plans/redisson-global-three-20261111-091713-6359e1d3.md，用户明确要求提升到每秒 3 次；该数值覆盖原执行计划的每秒 2 次初值。
- 聚合评审者：/root；独立参数复审由 /root/submission_rate_review 完成，见 reviews/independent-planning-review-global-three.md。原功能评审作为未变更部分的依据保留。
- 当前 8 项需求、24 个场景、13 项任务；新增 5.1、5.2 只覆盖参数及相关验证，不改变接口、用户冷却或容器池配置。

Java 和 bootstrap 同步改为 global-permits=3/global-interval=1s；用户 5 秒一次不变。配置绑定覆盖值与默认值分开；真实 Redis 验证三次放行、第四次拒绝、周期恢复和两个客户端共享三个许可，并验证存量 2 次状态不会由新配置自动覆盖，停服限定清理后才初始化为 3。

无阻塞项。新的运行报告须保留原始测试证据，旧 2 次报告不改写；此前容量实验不转换为 3 次吞吐结论。沿用用户已授权的 Docker 隔离验证与清理，测试不连接真实业务 Redis、不部署、不推送、不归档。实际更改范围为 6 个业务配置/测试/维护文件；其余源码和依赖配置保持原状。

## 之前每秒 2 次版本的规划评审（历史记录）

# 规划评审

- 变更：judge-submission-rate-limit
- 风险等级：strict（.openspec.yaml 的 x-workflow.workflow_profile）
- 结论：PASS
- 核验方式：machine
- 聚合评审者：/root，规划作者；独立语义评审由 /root/submission_rate_review 完成。
- 独立依据：reviews/independent-planning-review.md。
- 基线：proposal、specs、design、tasks、元数据、用户执行计划与本项目工作流规则；机器记录进一步绑定实际文件。

## 范围与来源核对

已对照用户执行计划检查两层默认额度、两入口共享、鉴权身份、先用户后全局的尝试计数、HTTP 429/503、单机连接复用、惰性有界客户端、用户 TTL、停服更新及隔离测试清理。当前 8 项需求、24 个场景与 11 项任务覆盖这些要求，没有将容量试运行值声明为吞吐验证结论。

代码检查确认 prepareSubmission 在两条提交链路的 try/catch 之前调用，适合在查询题目和受理副作用之前拒绝。新增错误码尚未占用，公共异常处理器需要单独设置真实 HTTP 状态；原有业务异常不改变。现有缓存通过 Lettuce，因此客户端并存与局部 Netty 对齐有实际回归安排。

## 阻塞项

无。独立规划评审 PASS，当前 openspec validate judge-submission-rate-limit --strict --no-interactive 退出码 0。

## 兼容与非阻塞注意事项

1. 首次 CLI 严格校验因根级 workflow_profile 自定义字段发出警告；现以 x-workflow 包含同一 workflow_profile: strict，CLI 严格校验通过。仍按 strict 执行独立评审与机器证据，不修改共享规则。
2. Redisson / Netty / Lettuce 的兼容结论需要实际构建及真实 Redis 测试；规划通过不作为测试证据。
3. 用户授权清理临时日志，故验收前将 XML 和日志并入保留报告，证据绑定该报告；不得保留失效路径。
4. 源码中已有沙箱防护工作，基线记录 64 个已修改或未跟踪文件；本轮只修改提交入口及其公共错误映射，不恢复或覆盖既有工作。

## 实施交接

依次完成客户端与配置、限流及提交边界、维护说明、真实 Redis 与回归检查。JDK 17 使用既有 Docker Maven 镜像；仅本轮 Redis 7.2-alpine 及独占网络/容器参与测试。用户已经授权本计划的规划、独立评审、实施、真实隔离测试、清理与报告保留。

原始规划产物、用户计划与工作流规则为持续约束；oj-friend 源码/POM 及公共 ResultCode、GlobalExceptionHandler 为允许修改的实施参考。正式开始前执行 record-plan、check-plan、start-apply。实现完成后还需独立代码评审、需求映射与正式验收；本报告不授权部署、推送或归档。

