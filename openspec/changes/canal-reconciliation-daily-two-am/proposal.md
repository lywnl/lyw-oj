# Proposal

## Why

用户要求每日北京时间 02:00 的对账由已有 XXL-JOB 和 oj-job 统一管理，提供手动触发与真实结果。替换本地 Cron 触发，保留实际对账算法及增量写入顺序。

## What Changes

- oj-job 新增 Handler，通过专用 Feign 调用唯一活动同步实例的受保护内部接口。
- 单请求槽将触发交给原消费线程，清理完成后回传统计；错误与取消不得误报成功或开启并发对账。
- 内部令牌、网关禁止、默认关闭与配置校验；移除本地 Cron/间隔，显式旧配置必须迁移。
- 保留 500 条分页、空闲拉取 1 秒和维护命令；增加单元、HTTP 和隔离真实 XXL-JOB 链路验收。

## Capabilities

### New Capabilities

- `question/index-sync`：沿用尚未归档的能力，替换触发并增加权限及有界执行约束。

### Modified Capabilities

无同名正式规范。旧规划/验收/任务保存在 reviews/history/local-cron-before-xxl，不作为新实现已通过的依据。

## Impact

涉及 oj-api、oj-job、oj-system、oj-gateway、部署说明和测试。新增内部权限边界，等级提升 strict，要求独立规划与代码质量评审。不新增调度服务、表、Redis 锁或对账线程，不迁移竞赛任务，不修改线上 Nacos/Admin/数据库。

来源：用户授权的 .codex/plans/canal-reconciliation-xxl-job-20261111.md，现有 Runner/Reconciler、ExamXxlJob、公共鉴权与网关代码。保留已有未提交改动及空闲拉取 1 秒行为。
