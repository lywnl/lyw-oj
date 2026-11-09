# 判题提交幂等与系统失败反馈

## Why

当前消息没有固定提交 ID，结果在判题结束后生成新主键；重复投递可能重复入库。等待、真实结果和死信均无条件写同一个题目缓存，迟到失败可能覆盖真实结果，页面又会在失败时停止查询。

## What Changes

- **BREAKING**：提交回传字符串 submitId，查询改为 submitId 与可选 checkDatabase，不保留旧参数或旧消息兼容。
- 固定 ID 随消息重试传递，用现有主键实现结果入库幂等；系统异常不写提交表。
- 每次提交一个缓存键，通过原子状态更新保护真实结果，内部等待标记只用于展示。
- 普通轮询只读缓存；系统提示后仍在 60 秒内继续查询，结束前与手动查询低频核对数据库。
- 死信写缓存有限重试，持续故障只暂停死信监听，保留未确认消息。

## Capabilities

### New Capabilities

- `judge/submission-results`：提交身份、真实结果持久化与消息幂等、统一缓存、归属查询、前端限时查询。

### Modified Capabilities

- `judge/container-pool`：细化容量拒绝后的临时状态和死信保留，以及真实结果保存与消息确认边界；保持池调度规则。

## Impact

- 后端根 `/Users/lllyw/lyw-oj`：oj-api、oj-common-redis/core、oj-friend、oj-judge；不修改数据库表或排名统计。
- 前端根 `/Users/lllyw/oj-fe-c`：Answer 页面、请求封装、题目与提交 API。
- strict，跨根 manual 核验；需要独立规划与代码评审，以及隔离 MySQL/Redis/RabbitMQ 和前端真实构建验证。
- 来源：本次用户会话与 `.codex/plans/判题提交幂等与系统失败反馈改造执行计划.md`，无新增外部需求文档。已有容器池规范与实际源码提供实现基线。
- 用户已授权保存计划并实施；不自动提交、推送、部署或归档，不删除既有基准报告。
