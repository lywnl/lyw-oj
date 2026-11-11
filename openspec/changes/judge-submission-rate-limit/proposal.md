# Proposal

## Why

两个提交入口目前共用受理逻辑，但没有限制单个用户和全部请求进入判题系统的速度。为控制重复提交和集中提交带来的排队压力，本轮在受理前增加共享的两层频率检查。

## What Changes

- 基于 Redisson 实现每用户 5 秒 1 次、全部用户和实例合计每秒 3 次，普通与 MQ 提交跨入口共享。
- 基础参数与身份检查后，先用户后全局；超过额度或 Redis 检查失败时阻止后续受理。
- 新增 HTTP 429 / 业务码 3602、3603，以及 HTTP 503 / 业务码 3604，沿用现有 Result 响应结构。
- 增加客户端配置、数据生命周期、停服统一更新说明及真实 Redis 验证。
- 每秒 3 次仅为试运行初值；未完成任务配额、IP/查询限流、热更新、容器池调度改造和吞吐压测均不在本轮范围内。

## Capabilities

### New Capabilities

- `judge/submission-rate-limit`：登录用户提交的分布式频率检查、拒绝行为及规则生命周期。

### Modified Capabilities

无。现有 `judge/container-pool` 管理执行资源调度，本轮增加的是受理前的频率检查。

## Impact

- 业务实现集中于 oj-friend：独立 Redisson 客户端、规则配置、SubmissionRateLimiter 与 prepareSubmission 接入。
- 公共 ResultCode 与异常处理器仅补充新增错误码及对应 HTTP 状态。
- 新增 org.redisson:redisson:3.52.0；仅 oj-friend 对齐 Netty 4.1.127.Final。Spring Boot 3.0.1 和现有 Lettuce 缓存接入继续使用。
- 风险等级 strict：涉及鉴权用户额度隔离、并发准入及公共错误响应，需要独立规划与代码评审、真实 Redis 验证。
- 来源：`.codex/plans/redisson-submit-rate-limit-20261110-215050-dc890d08.md` 中保存的用户执行计划、现有 UserQuestionServiceImpl/GlobalExceptionHandler/RedisConfig 及相关测试。外部 Redisson API/POM 用于实现参考，不作为用户授权。
- 授权覆盖规划、独立评审、编码、Docker 镜像拉取与隔离测试、仅本轮临时资源清理、报告保留；不包含推送、发布、操作真实业务 Redis 或归档。


## 2026-11-11 参数修订

用户明确要求将全局默认额度提升到每秒 3 次；用户 5 秒 1 次和其他行为保持原约定。最新来源为 `.codex/plans/redisson-global-three-20261111-091713-6359e1d3.md`，其中全局数值覆盖原执行计划的 2 次初值。此前 2 次版本报告继续作为历史证据；本轮不操作业务 Redis，不形成新的容量结论。
