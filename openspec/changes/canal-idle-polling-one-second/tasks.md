# Tasks

## 1. 空闲拉取间隔

- [x] 1.1 将 `SyncProperties.idleMillis` 默认值调整为 1000，并同步 `deploy/canal/README.md`；核对默认值、毫秒单位、配置覆盖及仅空闲等待语义一致，消费循环不发生改动。
- [x] 1.2 依赖 1.1，在隔离 JDK 17 构建中运行现有 `SyncConfigurationTest`、`QuestionEsSyncRunnerTest`、`TcpCanalSourceTest`，确认配置与消费回归通过；执行 `git diff --check` 并保留证据，清理本轮构建副本和测试容器。

## Workflow follow-up

- 完成聚焦验收后保留报告；未获指令不归档、提交或部署。
