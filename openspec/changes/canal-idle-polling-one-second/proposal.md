# Proposal

## Why

用户在讨论 Canal 空轮询开销后明确要求“改为 1s 拉取一次”。本次承接上下文，将空闲等待从 200 毫秒调整为 1000 毫秒，减少没有变更时的拉取请求。

## What Changes

- 调整 `canal.sync.idle-millis` 的 Java 默认值并同步维护文档。
- 沿用有工作时连续消费、外部配置覆盖、原有 ACK、重试与对账行为。
- 仅修改仓库文件并验证，不修改正在运行的服务或 Nacos 配置。

## Capabilities

### New Capabilities

- `question/index-sync`：沿用在途变更 `canal-question-es-sync` 的能力路径，补充空闲拉取间隔要求；该能力尚未归入正式规范，本次不重做原同步功能。

### Modified Capabilities

无已归档的相关能力需要修改。原变更的设计和验收保留为当时版本的记录，本次后续调整单独登记。

## Impact

影响 `SyncProperties.java` 与 `deploy/canal/README.md`；开发、生产配置均未显式覆盖空闲间隔，无需额外配置改动。没有新依赖、公共接口、数据模型或安全策略变更，风险等级为 fast。

来源为本次用户指令、当前消费循环及配置源码，无外部需求文档。验证采用现有配置绑定、消费顺序和 TCP 连接管理测试，并核对文档及差异。
